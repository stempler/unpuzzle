package org.akhikhl.unpuzzle.eclipse2maven

import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle;
import org.akhikhl.unpuzzle.osgi2maven.Pom

class ArtifactModManager {

  private final DependenciesConfig depConfig
  
  /**
   * Group -> Name -> Version -> Config
   */
  private final Map storedConfigs = [:]
  
  ArtifactModManager(DependenciesConfig depConfig) {
    this.depConfig = depConfig
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
    ArtifactConfig result = getArtifactConfig(dependency.group, dependency.name, dependency.version)
    if (result == null) {
      /*
       * Seems a missing dependency, but may still be OK if the dependency is
       * not to be deployed but can be found elsewhere.
       */
      
      // create dummy Pom
      Pom pom = new Pom(group: dependency.group, artifact: dependency.name, version: dependency.version)
      
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
