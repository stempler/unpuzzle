/*
 * unpuzzle
 *
 * Copyright 2014  Andrey Hihlovskiy.
 *
 * See the file "LICENSE" for copying and usage permission.
 */
package org.akhikhl.unpuzzle.eclipse2maven

import org.apache.commons.codec.digest.DigestUtils
import org.osgi.framework.Constants;
import org.osgi.framework.VersionRange
import org.osgi.framework.Version as OSGiVersion
import org.akhikhl.unpuzzle.utils.IConsole
import org.akhikhl.unpuzzle.utils.SysConsole
import org.akhikhl.unpuzzle.osgi2maven.Pom
import org.akhikhl.unpuzzle.osgi2maven.Bundle2Pom
import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle
import org.akhikhl.unpuzzle.osgi2maven.Deployer

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Deploys eclipse plugins to maven.
 * @author akhikhl
 */
final class EclipseDeployer {

  static File getUnpackDir(File targetDir, EclipseSource source) {
    String url = source.url
    String fileName = url.substring(url.lastIndexOf('/') + 1)
    new File(targetDir, 'unpacked/' + Utils.getArchiveNameNoExt(fileName))
  }

  protected static final Logger log = LoggerFactory.getLogger(EclipseDeployer)

  private final File targetDir
  private final String eclipseGroup
  private final Deployer mavenDeployer
  private final Deployer snapshotDeployer
  private final DependenciesConfig depConfig
  private final Closure verifyDependency
  private final IConsole console
  private final String installGroupPath
  private final String installGroupChecksum
  private Map artifacts = [:]
  private Map artifactsNl = [:]
  private Map artifactFiles = [:]
  private Map sourceFiles = [:]

  EclipseDeployer(File targetDir, String eclipseGroup, Deployer mavenDeployer, Deployer snapshotDeployer = null,
    IConsole console = null, DependenciesConfig depConfig = new DependenciesConfig(),
    Closure verifyDependency = null) {
    
    this.targetDir = targetDir
    this.eclipseGroup = eclipseGroup
    this.mavenDeployer = mavenDeployer
    this.snapshotDeployer = snapshotDeployer ? snapshotDeployer : mavenDeployer
    this.depConfig = depConfig
    this.verifyDependency = verifyDependency
    this.console = console ?: new SysConsole()
    installGroupPath = mavenDeployer.repositoryUrl.toString() + '/' + (eclipseGroup ? eclipseGroup.replace('.', '/') : '')
    installGroupChecksum = DigestUtils.md5Hex(installGroupPath)
  }

  boolean allDownloadedPackagesAreInstalled(List<EclipseSource> sources) {
    if(mavenDeployer.repositoryUrl.protocol == 'file') {
      for(EclipseSource source in sources) {
        String url = source.url
        String fileName = url.substring(url.lastIndexOf('/') + 1)
        String downloadedChecksum
        File downloadedChecksumFile = new File(targetDir, "downloaded-checksums/${fileName}.md5")
        if(downloadedChecksumFile.exists())
          downloadedChecksum = downloadedChecksumFile.text
        if(downloadedChecksum == null) {
          log.info 'allDownloadedPackagesAreInstalled, url={}, downloadedChecksum={}, returning false', url, downloadedChecksum
          return false
        }
        String installedChecksum
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        if(installedChecksumFile.exists())
          installedChecksum = installedChecksumFile.text
        if(downloadedChecksum != installedChecksum) {
          log.info 'allDownloadedPackagesAreInstalled, url={}, downloadedChecksum={}, installedChecksum={}, returning false', url, downloadedChecksum, installedChecksum
          return false
        }
      }
      log.info 'allDownloadedPackagesAreInstalled, repository={}, all checksums match, returning true', mavenDeployer.repositoryUrl
      return true
    }
    log.info 'allDownloadedPackagesAreInstalled, repository={}, non-file protocol, returning false', mavenDeployer.repositoryUrl
    return false
  }

  boolean allDownloadedPackagesAreUninstalled(List<EclipseSource> sources) {
    if(mavenDeployer.repositoryUrl.protocol == 'file') {
      for(EclipseSource source in sources) {
        String url = source.url
        String fileName = url.substring(url.lastIndexOf('/') + 1)
        String downloadedChecksum
        File downloadedChecksumFile = new File(targetDir, "downloaded-checksums/${fileName}.md5")
        if(downloadedChecksumFile.exists())
          downloadedChecksum = downloadedChecksumFile.text
        String installedChecksum
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        if(installedChecksumFile.exists())
          installedChecksum = installedChecksumFile.text
        if(downloadedChecksum != null && downloadedChecksum == installedChecksum) {
          log.info 'allDownloadedPackagesAreUninstalled, url={}, checksum match, returning false', url
          return false
        }
      }
      log.info 'allDownloadedPackagesAreUninstalled, repository={}, all checksums removed, returning true', mavenDeployer.repositoryUrl
      return true
    }
    log.info 'allDownloadedPackagesAreUninstalled, repository={}, non-file protocol, returning false', mavenDeployer.repositoryUrl
    return false
  }

  private void collectArtifactsInFolder(EclipseSource source, artifactsSourceDir) {
    def processFile = { File file ->
      console.info("Collecting artifacts: ${file.name}")
      try {
        Bundle2Pom reader = new Bundle2Pom(group: eclipseGroup, dependencyGroup: eclipseGroup)
        Pom pom = reader.convert(file)
        if (depConfig.isExcluded(pom.artifact)) {
          return
        }
        
        def source_match = pom.artifact =~ /(.*)\.source$/
        if(source_match) {
          def artifact = source_match[0][1]
          if (!depConfig.isExcluded(artifact)) {
            sourceFiles["${artifact}:${pom.version}"] = file
          }
        } else if(!source.sourcesOnly) {
          def nl_match = pom.artifact =~ /(.*)\.nl_(.*)/
          boolean excluded = false
          if(nl_match) {
            def artifact = nl_match[0][1]
            def language = nl_match[0][2]
            if (!depConfig.isExcluded(artifact)) {
              if(!artifactsNl[language]) {
                artifactsNl[language] = [:]
              }
              artifactsNl[language][artifact] = pom
            }
            else {
              return
            }
          } else if(!source.languagePacksOnly) {
            if(!artifacts.containsKey(pom.artifact)) {
              artifacts[pom.artifact] = []
            }
            artifacts[pom.artifact].add pom
          }
          artifactFiles["${pom.artifact}:${pom.version}"] = file
        }
      } catch (Exception e) {
        console.info("Error while mavenizing ${file}")
        e.printStackTrace()
      }
    }
    console.startProgress("Reading bundles in $artifactsSourceDir")
    try {
      artifactsSourceDir.eachDir processFile
      artifactsSourceDir.eachFileMatch ~/.*\.jar/, processFile
    } finally {
      console.endProgress()
    }
  }
  
  private void addToPackageIndex(EclipseSource source, artifactsSourceDir, PackageIndex index) {
    def processFile = { File file ->
      Bundle2Pom reader = new Bundle2Pom(group: eclipseGroup, dependencyGroup: eclipseGroup)
      Pom pom = reader.convert(file)
      def source_match = pom.artifact =~ /(.*)\.source$/
      if(!source_match && !depConfig.isExcluded(pom.artifact)) {
        index.addBundle(pom, file)
      }
    }
    if (!source.sourcesOnly) { // sources with only sources can be skipped
      console.startProgress("Building index of exported packages for bundles in $artifactsSourceDir")
      try {
        artifactsSourceDir.eachDir processFile
        artifactsSourceDir.eachFileMatch ~/.*\.jar/, processFile
      } finally {
        console.endProgress()
      }
    }
  }
  
  private void addPackageDependencies(PackageIndex index) {
    // add dependencies based on package imports
    console.startProgress("Determining package-based dependencies based on package imports")
    try {
      artifacts.each { name, artifactVersions ->
        artifactVersions.each { pom ->
          File bundleFile = artifactFiles["${pom.artifact}:${pom.version}"]
          index.extendDependencies(pom, bundleFile)
        }
      }
    } finally {
      console.endProgress()
    }
  }

  void deploy(List<EclipseSource> sources) {

    File installedCheckumsInfoFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/info.txt")
    installedCheckumsInfoFile.parentFile.mkdirs()
    installedCheckumsInfoFile.text = """eclipseGroup=$eclipseGroup
installGroupPath=$installGroupPath"""

    PackageIndex index = new PackageIndex(console: console)
    
    for(EclipseSource source in sources) {
      String url = source.url
      String fileName = url.substring(url.lastIndexOf('/') + 1)
      File unpackDir = new File(targetDir, "unpacked/${Utils.getArchiveNameNoExt(fileName)}")
      boolean packageInstalled = false
      if(mavenDeployer.repositoryUrl.protocol == 'file') {
        String downloadedChecksum
        File downloadedChecksumFile = new File(targetDir, "downloaded-checksums/${fileName}.md5")
        if(downloadedChecksumFile.exists())
          downloadedChecksum = downloadedChecksumFile.text
        String installedChecksum
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        if(installedChecksumFile.exists())
          installedChecksum = installedChecksumFile.text
        packageInstalled = downloadedChecksum == installedChecksum
      }
      File pluginFolder = new File(unpackDir, 'Contents/Eclipse/plugins')
      if (!pluginFolder.exists()) {
        pluginFolder = new File(unpackDir, 'plugins')
        if (!pluginFolder.exists()) {
          pluginFolder = unpackDir
        }
      }
      if(!packageInstalled) {
        collectArtifactsInFolder(source, pluginFolder)
      }
      // all bundles are added to the package index
      addToPackageIndex(source, pluginFolder, index)
    }

    // augment pom's with dependencies based on package imports
    addPackageDependencies(index)

    // write package dependencies report files
    index.writeReports(targetDir)
    
    // load artifact modifications from configuration
    ArtifactModManager mods
    if (depConfig.hasArtifactConfigs()) {
      mods = loadArtifactModifications()
    }
    
    // fix dependency versions, apply artifact modifications
    fixDependencies(mods)
    
    // apply artifact modifications to Poms, dependencies and files
    if (mods) {
      applyArtifactModifications(mods)
    }

    // deployment
    console.startProgress('Deploying artifacts')
    try {
      artifacts.each { name, artifactVersions ->
        artifactVersions.each { pom ->
          def artifactFile = artifactFiles["${pom.artifact}:${pom.version}"]
          // artifact file reference may have been removed (because artifact should not be deployed)
          if (artifactFile) {
            def deployer = pom.version?.endsWith('-SNAPSHOT') ? snapshotDeployer : mavenDeployer
            deployer.deployBundle pom, artifactFile, sourceFile: sourceFiles["${pom.artifact}:${pom.version}"]
          }
        }
      }
      artifactsNl.each { language, map_nl ->
        map_nl.each { artifactName, pom ->
          def deployer = pom.version?.endsWith('-SNAPSHOT') ? snapshotDeployer : mavenDeployer
          deployer.deployBundle pom, artifactFiles["${pom.artifact}:${pom.version}"]
        }
      }
    } finally {
      console.endProgress()
    }

    if(mavenDeployer.repositoryUrl.protocol == 'file') {
      for(EclipseSource source in sources) {
        String url = source.url
        String fileName = url.substring(url.lastIndexOf('/') + 1)
        File downloadedChecksumFile = new File(targetDir, "downloaded-checksums/${fileName}.md5")
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        installedChecksumFile.parentFile.mkdirs()
        installedChecksumFile.text = downloadedChecksumFile.text
      }
    }
  }
  
  private ArtifactModManager loadArtifactModifications() {
    ArtifactModManager mods = new ArtifactModManager(depConfig, eclipseGroup, console)
    
    // load the configuration for all existing artifacts
    artifacts.each { name, artifactVersions ->
      artifactVersions.each { Pom pom ->
        def file = artifactFiles["${pom.artifact}:${pom.version}"]
        
        mods.loadArtifactConfig(pom, file)
      }
    }
    
    return mods
  }
  
  private applyArtifactModifications(ArtifactModManager mods, boolean skipVerify = false) {
    def verifyDependencies = new HashSet<String>()
    
    console.startProgress('Applying artifact modifications')
    try {
      artifacts.each { name, artifactVersions ->
        artifactVersions.each { Pom pom ->
          
          String fileIdent = "${pom.artifact}:${pom.version}"
          def artifactFile = artifactFiles.remove(fileIdent)
          def sourceFile = sourceFiles.remove(fileIdent)
          
          // adapt Pom
          ArtifactConfig pomConfig = mods.getConfig(pom, artifactFile)
          pomConfig.apply(pom)
          
          // update files for deployment
          if (pomConfig.deploy) {
            // re-add files
            fileIdent = "${pom.artifact}:${pom.version}"
            artifactFiles[fileIdent] = artifactFile
            if (sourceFile) {
              sourceFiles[fileIdent] = sourceFile
            }
          }
          else {
            // should not be deployed
            // collect for verification
            verifyDependencies << "$pom.group:$pom.artifact:$pom.version" as String
          }
          
          // update all dependencies
          
          List<DependencyBundle> otherPartDependencies = []
          
          pom.dependencyBundles.each { DependencyBundle depBundle ->
            ArtifactConfig depConfig = mods.getConfig(depBundle)
            // apply configuration to dependency (so reference is correct)
            depConfig.apply(depBundle)
            if (!depConfig.deploy) {
              // collect for verification
              verifyDependencies << "$depBundle.group:$depBundle.name:$depBundle.version" as String
            }
            
            if (depConfig.otherParts) {
              // collect additional dependencies to add
              depConfig.otherParts.each { it ->
                otherPartDependencies << new DependencyBundle(
                  group: it.group,
                  name: it.name,
                  resolution: Constants.RESOLUTION_MANDATORY,
                  visibility: Constants.VISIBILITY_PRIVATE,
                  version: it.version)
              }
            }
          }
          
          otherPartDependencies.each { depBundle ->
            //TODO check for duplicates / dependencies already present?
            
            // add as dependency
            pom.dependencyBundles << depBundle
            // collect for verification - "other parts" are never deployed
            verifyDependencies << "$depBundle.group:$depBundle.name:$depBundle.version" as String
          }
          
        }
      }
      
      //TODO handle artifactsNl as well?
    } finally {
      console.endProgress()
    }
    
    // verify dependencies that are not deployed but should reference
    // an artifact that is available already
    if (!skipVerify && depConfig.verifyIfNoDeploy) {
      console.startProgress('Verifying artifacts that are not deployed')
      try {
        console.info(verifyDependencies.size() + ' artifacts to verify...')
        if (verifyDependency == null) {
          throw new IllegalStateException('Handler for verifying artifact not available')
        }
        
        verifyDependencies.each { dep ->
          boolean valid = verifyDependency(dep)
          if (!valid) {
            throw new IllegalStateException("Validation failed: Could not resolve dependency $dep")
          }
        }  
      }
      finally {
        console.endProgress()
      }
    }
  }

  private void fixDependencies(ArtifactModManager mods = null) {
    console.startProgress('Fixing dependencies')
    try {
      artifacts.each { name, artifactVersions ->
        console.info("Fixing dependencies: $name")
        artifactVersions.each { Pom pom ->
          
          // collect dependency names
          Set<String> dependencyNames = new HashSet<String>()
          pom.dependencyBundles.each { DependencyBundle reqBundle ->
            dependencyNames << reqBundle.name.trim()
          }
          
          // apply replacements
          pom.dependencyBundles.removeAll { DependencyBundle reqBundle ->
            def replacement
            if (reqBundle.group == null || reqBundle.group == eclipseGroup) {
              replacement = depConfig.getReplacement(reqBundle.name.trim())
            }
            if (replacement) {
              if (!dependencyNames.contains(replacement)) {
                // replace only if not yet a dependency
                
                // replace bundle name
                reqBundle.name = replacement
                
                return false
              }
              else {
                // remove dependency - replacement is already a dependency
                return true
              }
            } else {
              // keep dependency as-is
              return false
            }
          }
          
          // remove all dependencies that cannot be found (for any version)
          pom.dependencyBundles.removeAll { reqBundle ->
            if(!artifacts[reqBundle.name.trim()]) {
              def config = mods?.getConfig(reqBundle)
              if (config == null || config.deploy) {
                // artifact should be deployed but is not present
                console.info("Warning: artifact dependency $pom.group:$pom.artifact:$pom.version -> $reqBundle.name could not be resolved.")
                return true
              }
              else {
                // artifact is assumed to be available elsewhere
                return false
              }
            }
            return false
          }
          
          // fix version of dependencies based on available artifacts
          pom.dependencyBundles.each { reqBundle ->
            def resolvedVersions = artifacts[reqBundle.name.trim()]
            if (!resolvedVersions || resolvedVersions.isEmpty()) {
              /*
               * Ignore - missing dependency that is not deployed (otherwise it
               * would have been removed in the previous step). 
               */
            }
            else if (resolvedVersions.size() == 1) {
              reqBundle.version = resolvedVersions[0].version
            }
            else {
              // normalise dependency version (may be a range)
              VersionRange versionRange = new VersionRange(reqBundle.version.trim() ?: '0.0.0')
              String depVersion = versionRange.getLeft().toString()
               
              if (resolvedVersions.find { it -> it.version == depVersion }) {
                // use normalised version, there is a direct match
                reqBundle.version = depVersion
              } else {
                def compare = { a, b -> new Version(a).compare(new Version(b)) }
                resolvedVersions = resolvedVersions.sort(compare)
                
                // find a version that is in the version range
                def matchedDep = resolvedVersions.find { dep ->
                  def osgiVersion = new OSGiVersion(dep.version)
                  versionRange.includes(osgiVersion)
                }
                
                if (!matchedDep) {
                  // if no match, use original way of matching
                  int i = Collections.binarySearch resolvedVersions, reqBundle.version.trim(), compare as java.util.Comparator
                  if(i < 0) {
                    i = -i - 1
                  }
                  if(i > resolvedVersions.size() - 1) {
                    i = resolvedVersions.size() - 1
                  }
                  matchedDep = resolvedVersions[i]
                }
                
                def depsStr = resolvedVersions.collect({ p -> "$p.group:$p.artifact:$p.version" }).join(', ')
                console.info("Warning: resolved ambiguous dependency: $pom.group:$pom.artifact:$pom.version -> $reqBundle.name:$reqBundle.version, chosen $matchedDep.group:$matchedDep.artifact:$matchedDep.version from [$depsStr].")
                reqBundle.version = matchedDep.version
              }
            }
          }
          artifactsNl.each { language, map_nl ->
            def pom_nl = map_nl[pom.artifact]
            if(pom_nl)
              pom.dependencyBundles.each { dep_bundle ->
                def dep_pom_nl = map_nl[dep_bundle.name]
                if(dep_pom_nl) {
                  pom_nl.dependencyBundles.add new DependencyBundle(name: dep_pom_nl.artifact, version: dep_pom_nl.version, visibility: dep_bundle.visibility, resolution: dep_bundle.resolution)
                }
              }
          }
        }
      }
    } finally {
      console.endProgress()
    }
  }

  void uninstall(List<EclipseSource> sources) {

    if(mavenDeployer.repositoryUrl.protocol != 'file') {
      console.progressError("Could not uninstall from non-file URL: ${mavenDeployer.repositoryUrl}")
      return
    }

    File repositoryDir = new File(mavenDeployer.repositoryUrl.toURI())

    for(EclipseSource source in sources) {
      String url = source.url
      String fileName = url.substring(url.lastIndexOf('/') + 1)
      File unpackDir = new File(targetDir, "unpacked/${Utils.getArchiveNameNoExt(fileName)}")
      boolean packageInstalled = false
      if(mavenDeployer.repositoryUrl.protocol == 'file') {
        String downloadedChecksum
        File downloadedChecksumFile = new File(targetDir, "downloaded-checksums/${fileName}.md5")
        if(downloadedChecksumFile.exists())
          downloadedChecksum = downloadedChecksumFile.text
        String installedChecksum
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        if(installedChecksumFile.exists())
          installedChecksum = installedChecksumFile.text
        packageInstalled = downloadedChecksum == installedChecksum
      }
      if(packageInstalled) {
        File pluginFolder = new File(unpackDir, 'Contents/Eclipse/plugins')
        if (!pluginFolder.exists()) {
          pluginFolder = new File(unpackDir, 'plugins')
          if (!pluginFolder.exists()) {
            pluginFolder = unpackDir
          }
        }
        collectArtifactsInFolder(source, pluginFolder)
      }
    }

    // load artifact modifications from configuration
    ArtifactModManager mods
    if (depConfig.hasArtifactConfigs()) {
      mods = loadArtifactModifications()
    }
    
    // fix dependency versions, apply artifact modifications
    fixDependencies(mods)
    
    // apply artifact modifications to Poms, dependencies and files
    if (mods) {
      applyArtifactModifications(mods, true)
    }

    def deleteArtifactDir = { Pom pom ->
      def group = pom.group ?: eclipseGroup
      
      // groups with dots are represented in nested directories
      def groupDir = group.replaceAll(/\./, '/')
      
      File artifactDir = new File(repositoryDir, "${groupDir}/${pom.artifact}")
      if(artifactDir.exists())
        artifactDir.deleteDir()
    }

    console.startProgress('Uninstalling artifacts')
    try {
      artifacts.each { name, artifactVersions ->
        artifactVersions.each deleteArtifactDir
      }
      artifactsNl.each { language, map_nl ->
        map_nl.each { artifactName, pom ->
          deleteArtifactDir(pom)
        }
      }
    } finally {
      console.endProgress()
    }

    if(mavenDeployer.repositoryUrl.protocol == 'file')
      for(EclipseSource source in sources) {
        String url = source.url
        String fileName = url.substring(url.lastIndexOf('/') + 1)
        File installedChecksumFile = new File(targetDir, "installed-checksums/${installGroupChecksum}/${fileName}.md5")
        installedChecksumFile.delete()
      }
  }
}
