package org.akhikhl.unpuzzle.eclipse2maven

import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle;
import org.akhikhl.unpuzzle.osgi2maven.Pom
import org.akhikhl.unpuzzle.utils.IConsole

class ArtifactModManager {

  private final DependenciesConfig depConfig
  
  /**
   * Group -> Name -> Version -> Config
   */
  private final Map storedConfigs = [:]
  
  private final String defaultGroup
  
  private final IConsole console
  
  ArtifactModManager(DependenciesConfig depConfig, String defaultGroup, IConsole console) {
    this.depConfig = depConfig
    this.defaultGroup = defaultGroup
    this.console = console
  }
  
  /**
   * Load the artifact configuration for a bundle.
   * 
   * @param pom the Pom identifying the bundle
   * @param file the bundle file
   */
  ArtifactConfig loadArtifactConfig(Pom pom, File file) {
    ArtifactConfig config = depConfig.getArtifactConfig(pom, file)
    
    Map grouped = storedConfigs[pom.group]
    if (!grouped) {
      grouped = [:]
      storedConfigs[pom.group] = grouped
    }
    Map named = grouped[pom.artifact]
    if (!named) {
      named = [:]
      grouped[pom.artifact] = named
    }
    named[pom.version] = config
    
    config
  }
  
  /**
   * Get a loaded artifact configuration.
   * 
   * @param group the artifact group
   * @param name the artifact name
   * @param version the artifact version
   * @return the artifact configuration or <code>null</code>
   */
  private ArtifactConfig getArtifactConfig(String group, String name, String version) {
    ArtifactConfig result
    Map grouped = storedConfigs[group]
    if (grouped) {
      Map named = grouped[name]
      if (named) {
        result = named[version]
      }
    }
    
    result
  }
  
  /**
   * Get the artifact configuration for a dependency.
   * 
   * @param dependency the dependency
   * @return the artifact configuration
   */
  ArtifactConfig getConfig(DependencyBundle dependency) {
    def group = dependency.group ?: defaultGroup
    
    ArtifactConfig result = getArtifactConfig(group, dependency.name, dependency.version)
    if (result == null) {
      /*
       * Seems a missing dependency, but may still be OK if the dependency is
       * not to be deployed but can be found elsewhere.
       */
      
      console.info('[warn] Creating artifact configuration for missing dependency: ' + [group: group, artifact: dependency.name, version: dependency.version])
      
      // create dummy Pom
      Pom pom = new Pom(group: group, artifact: dependency.name, version: dependency.version)
      
      // load artifact configuration without a file
      result = loadArtifactConfig(pom, null)
    }
    
    result
  }
  
  ArtifactConfig getConfig(Pom pom, File file = null) {
    ArtifactConfig result = getArtifactConfig(pom.group, pom.artifact, pom.version)
    if (result == null) {
      // configuration that was not loaded
      // should not happen - return an unmodified configuration just to be safe to never return null
      result = new ArtifactConfig(pom, file)
    }
    result
  }
  
}
