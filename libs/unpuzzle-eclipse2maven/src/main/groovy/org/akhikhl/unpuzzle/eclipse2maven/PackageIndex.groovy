package org.akhikhl.unpuzzle.eclipse2maven

import java.util.jar.JarFile;
import java.util.jar.Manifest

import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle
import org.akhikhl.unpuzzle.osgi2maven.Pom
import org.akhikhl.unpuzzle.utils.IConsole
import org.akhikhl.unpuzzle.utils.SysConsole;
import org.osgi.framework.Constants

import aQute.bnd.header.OSGiHeader
import aQute.bnd.header.Parameters
import groovy.transform.CompileStatic;
import groovy.transform.Immutable;

@CompileStatic
class PackageProvider {
  Pom bundle
  String packageVersion
}

/**
 * Index on exported packages for a set of bundles.
 */
class PackageIndex {

  IConsole console = new SysConsole()

  private Map<String, List<PackageProvider>> index = [:]

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
      putPackage(pkg, pkgVersion, pom)
    }
  }

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

  private putPackage(String pkg, String version, Pom bundle) {
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

    def deps = [:]
    pkgs.each { pkg, attrs ->
      def pkgVersion = attrs[Constants.VERSION_ATTRIBUTE]
      def optional = attrs[Constants.RESOLUTION_DIRECTIVE] == Constants.RESOLUTION_OPTIONAL
      //FIXME version is ignored for now
      //FIXME optional is ignored for now

      def candidates = findPackage(pkg)
      if (candidates) {
        if (candidates.size() > 1) {
          //FIXME strategy to select?
          //XXX in some cases multiple, in some cases only one is applicable
          //XXX prefer dependency that is already added via require-bundle?
          def names = candidates.collect {
            it.bundle.artifact + ':' + it.bundle.version
          }
          console.progressError("Multiple candidates for imported package $pkg: $names")
        }
        else {
          def candidate = candidates[0].bundle
          if (pom.artifact != candidate.artifact) {
            if (deps[candidate.artifact]) {
              //TODO check if there is a version conflict?
            }
            else {
              // dependency not added yet
              deps[candidate.artifact] = candidate
            }
          }
        }
      }
    }

    mergeDependencies(pom, deps.values())
  }

  private void mergeDependencies(Pom parent, Collection<Pom> dependenciesToAdd) {
    // collect names of bundles already added
    def bundles = new HashSet<String>()
    for (DependencyBundle dep : parent.dependencyBundles) {
      bundles.add(dep.name)
    }

    for (Pom dependency : dependenciesToAdd) {
      if (!bundles.contains(dependency.artifact)) {
        parent.dependencyBundles << new DependencyBundle(
            group: dependency.group,
            name: dependency.artifact,
            resolution: Constants.RESOLUTION_MANDATORY,
            visibility: Constants.VISIBILITY_PRIVATE,
            version: dependency.version)

        console.info("Adding dependency ${parent.artifact} -> ${dependency.artifact}")
      }
    }
  }

  /**
   * Find possible providers for a package.
   * @param pgk the package that should be provided
   * @return the list of package providers exporting the package
   */
  List<PackageProvider> findPackage(String pkg) {
    //FIXME add version information to the call

    (index[pkg]?:[]).asImmutable()
  }

}

