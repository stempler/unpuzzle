package org.akhikhl.unpuzzle.eclipse2maven

import java.util.jar.JarFile;
import java.util.jar.Manifest

import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle
import org.akhikhl.unpuzzle.osgi2maven.Pom
import org.akhikhl.unpuzzle.utils.IConsole
import org.akhikhl.unpuzzle.utils.SysConsole;
import org.osgi.framework.Constants
import org.osgi.framework.VersionRange
import org.osgi.framework.Version as OSGiVersion

import aQute.bnd.header.OSGiHeader
import aQute.bnd.header.Parameters
import groovy.json.JsonOutput;
import groovy.transform.CompileStatic;
import groovy.transform.Immutable;

/**
 * Represents a package provider in the package index map.
 */
@CompileStatic
class PackageProvider {
  /** The bundle that is the provider */
  Pom bundle
  /** The version of the package that is provided */
  OSGiVersion packageVersion
}

/**
 * Represents a collected package dependency to merge with other dependencies of the bundle.
 */
@CompileStatic
class PackageDependency {
  /** The bundle that provides the dependency */
  Pom bundle
  /** If the dependency is optional */
  boolean optional
}

/**
 * Index on exported packages for a set of bundles.
 */
class PackageIndex {

  IConsole console = new SysConsole()

  /** The index map on collected exported packages */
  private Map<String, List<PackageProvider>> index = [:]
  
  /** Collected information on mandatory imports that could not be met */
  private Map<String, List<Map<String, String>>> mandatoryNotFound = [:]
  
  /** Collected information on optional imports that could not be met */
  private Map<String, List<Map<String, String>>> optionalNotFound = [:]
  
  /** 
   * Collected information on multiple candidates being found for an import
   * (which have a potential for conflicts)
   */
  private Map<String, Map<String, ?>> multipleCandidatesFound = [:]

  /**
   * Add a bundle to the package index.
   * 
   * @param pom the POM information already collected for the bundle
   * @param dirOrJar directory or JAR file representing an OSGi bundle
   */
  void addBundle(Pom pom, File dirOrJar) {
    Manifest manifest = loadManifest(dirOrJar)

    // determine package exports
    String exports = manifest.mainAttributes.getValue(Constants.EXPORT_PACKAGE)
    Parameters pkgs = OSGiHeader.parseHeader(exports)

    pkgs.each { pkg, attrs ->
      def pkgVersion = attrs[Constants.VERSION_ATTRIBUTE]
      putPackage(pkg, new OSGiVersion(pkgVersion ?: '0.0.0'), pom)
    }
  }

  /**
   * Load manifest information from a bundle.
   * 
   * @param dirOrJar the directory or Jar file representing the bundle
   * @return the loaded manifest
   */
  private Manifest loadManifest(File dirOrJar) {
    Manifest manifest
    if (dirOrJar.isDirectory()) {
      new File(dirOrJar, 'META-INF/MANIFEST.MF').withInputStream {
        manifest = new Manifest(it)
      }
    } else {
      manifest = new JarFile(dirOrJar).manifest
    }

    manifest
  }

  /**
   * Add a package to the index.
   * 
   * @param pkg the full qualified package name
   * @param version the package version that is provided
   * @param bundle the bundle that provides the package
   */
  private void putPackage(String pkg, OSGiVersion version, Pom bundle) {
    def provider = new PackageProvider(bundle: bundle, packageVersion: version)

    def providerList = index[pkg]
    if (providerList == null) {
      providerList = []
      index[pkg] = providerList
    }
    providerList << provider
  }

  /**
   * Extend the given POM with dependencies based on package imports.
   *
   * @param pom the POM information already collected for the bundle
   * @param dirOrJar directory or JAR file representing an OSGi bundle
   */
  void extendDependencies(Pom pom, File dirOrJar) {
    Manifest manifest = loadManifest(dirOrJar)

    // determine package imports
    String imports = manifest.mainAttributes.getValue(Constants.IMPORT_PACKAGE)
    Parameters pkgs = OSGiHeader.parseHeader(imports)

    // dependencies are bundle symbolic names mapped to PackageDependency
    def deps = [:]
    
    pkgs.each { pkg, attrs ->
      def pkgVersion = attrs[Constants.VERSION_ATTRIBUTE]
      VersionRange versionRange = new VersionRange(pkgVersion ?: '0.0.0')
      
      def optional = (attrs[Constants.RESOLUTION_DIRECTIVE + ':'] == Constants.RESOLUTION_OPTIONAL)
      
      def candidates = findPackage(pkg, versionRange)
      if (candidates) {
        if (candidates.size() > 1) {
          // XXX strategy to select/exclude dependencies cannot be decided here,
          // configuration is needed to adapt behavior as needed
          
          // by default, add all dependencies
          candidates.each { candidate ->
            collectDependency(pom, deps, candidate, optional)
          }
          
          logMultipleCandidates(pkg, versionRange.toString(), pom.artifact, candidates)
        }
        else {
          // add uniquely identified dependency for package
          collectDependency(pom, deps, candidates[0], optional)
        }
      }
      else {
        logPackageNotFound(pkg, versionRange.toString(), pom.artifact, optional)
      }
    }

    mergeDependencies(pom, deps.values())
  }
  
  /**
   * Collect the given dependency and at to the host's dependency map.
   * 
   * @param host the Pom describing the bundle we collect dependencies for
   * @param deps the map of dependencies for the host, bundle symbolic names
   *   mapped to PackageDependency
   * @param toAdd the dependency to add
   * @param optional if the dependency is optional
   */
  private void collectDependency(Pom host, deps, PackageProvider toAdd, boolean optional) {
    def candidate = toAdd.bundle
    if (host.artifact != candidate.artifact) { // don't add self as dependency
      PackageDependency current = deps[candidate.artifact]
      if (current) {
        // bundle is already added as dependency
        
        // mandatory overrides optional
        if (!optional) {
          current.optional = false
        }
        
        // use the dependency with the higher version number
        try {
          OSGiVersion currentVersion = new OSGiVersion(current.bundle.version ?: '0.0.0')
          OSGiVersion toAddVersion = new OSGiVersion(toAdd.bundle.version ?: '0.0.0')
          if (toAddVersion.compareTo(currentVersion) > 0) {
            // higher version number -> replace dependency
            current.bundle = toAdd.bundle
          }
        } catch (e) {
          // if this happens, likely the versions were no proper OSGi versions
          // still, this is probably not fatal (or fails at a different point anyway)
          console.progressError("Error comparing dependency versions")
          e.printStackTrace()
        }
      }
      else {
        // dependency not added yet -> simply add
        deps[candidate.artifact] = new PackageDependency(bundle: candidate, optional: optional)
      }
    }
  }

  /**
   * Merge package dependencies into existing bundle dependencies.
   * 
   * @param parent the Pom representing the bundle dependencies should be added to
   * @param dependenciesToAdd the dependencies to add to the bundle
   */
  private void mergeDependencies(Pom parent, Collection<PackageDependency> dependenciesToAdd) {
    // collect names of bundles already added
    def bundles = new HashSet<String>()
    for (DependencyBundle dep : parent.dependencyBundles) {
      bundles.add(dep.name)
    }

    // add _additional_ bundles that were identified through package imports
    // i.e. information from RequireBundle is preferred 
    for (PackageDependency dependency : dependenciesToAdd) {
      if (!bundles.contains(dependency.bundle.artifact)) {
        parent.dependencyBundles << new DependencyBundle(
            group: dependency.bundle.group,
            name: dependency.bundle.artifact,
            resolution: dependency.optional ? Constants.RESOLUTION_OPTIONAL : Constants.RESOLUTION_MANDATORY,
            visibility: Constants.VISIBILITY_PRIVATE,
            version: dependency.bundle.version)

        def optionalMsg = dependency.optional ? ' (optional)' : ''
        console.info("Adding dependency ${parent.artifact} -> ${dependency.bundle.artifact}$optionalMsg")
      }
    }
  }

  /**
   * Find possible providers for a package.
   * 
   * @param pgk the package that should be provided
   * @param versions the allowed version range
   * @return the list of package providers exporting the package
   */
  List<PackageProvider> findPackage(String pkg, VersionRange versions) {
    def providers = index[pkg]?:[]
    
    // yield only package providers that lie in the version range
    providers.findAll { provider ->
      versions.includes(provider.packageVersion)
    }
  }
  
  private def logPackageNotFound(String pkg, String version, String bundle, boolean optional) {
    //TODO ignore specific packages? e.g. built-in stuff like javax.swing?
    
    // console messages
    if (optional) {
      console.info("[warn] No bundle found providing optional package $pkg")
    }
    else {
      console.info("[warn] No bundle found providing package $pkg")
    }
    
    // store information for report
    def info = [bundle: bundle, requiresVersion: version]
    logPackageNotFound(optional ? optionalNotFound : mandatoryNotFound, pkg, info)
  }
  
  private def logPackageNotFound(Map<String, List<Map<String, String>>> map, String pkg, Map<String, String> info) {
    def list = map[pkg]
    if (!list) {
      list = []
      map[pkg] = list
    }
    list << info
  }
  
  private def logMultipleCandidates(String pkg, String version, String bundle, List<PackageProvider> candidates) {
    // warn that there were multiple providers of the same package
    def names = candidates.collect {
      it.bundle.artifact + ':' + it.bundle.version
    }
    console.info("[warn] Multiple candidates for imported package $pkg: $names")
    
    // store information for report
    def candidateMaps = candidates.collect { PackageProvider p ->
      [bundle: p.bundle.artifact, version: p.bundle.version, providesVersion: p.packageVersion.toString()]
    }
    def incident = [bundleExample: bundle, requiresVersion: version, candidates: candidateMaps]
    def oldIncident = multipleCandidatesFound[pkg]
    if (!oldIncident) {
      // no such incident yet
      incident.similarIncidents = 0
      multipleCandidatesFound[pkg] = incident
    }
    else if (oldIncident && oldIncident.candidates && oldIncident.candidates.size() < candidates.size()) {
      // replace incident
      incident.similarIncidents = oldIncident.similarIncidents + 1
      multipleCandidatesFound[pkg] = incident
    }
    else {
      oldIncident.similarIncidents++
    }
  }
  
  def writeReports(File reportDir) {
    // package not found reports
    File mandatoryFile = new File(reportDir, 'unsatisfiedPackages-mandatory.json')
    mandatoryFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(mandatoryNotFound))
    File optionalFile = new File(reportDir, 'unsatisfiedPackages-optional.json')
    optionalFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(optionalNotFound))
    
    // report on multiple candidates for package imports
    File multipleFile = new File(reportDir, 'packageExportOverlaps.json')
    multipleFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(multipleCandidatesFound))
  }

}

