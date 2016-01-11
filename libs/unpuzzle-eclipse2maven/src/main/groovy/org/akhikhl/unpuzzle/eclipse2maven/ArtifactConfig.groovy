package org.akhikhl.unpuzzle.eclipse2maven

import java.io.File

import org.akhikhl.unpuzzle.osgi2maven.DependencyBundle;
import org.akhikhl.unpuzzle.osgi2maven.Pom;

/**
 * Artifact configuration class. An artifact configuration closure is evaluated
 * with an instance of this class as delegate.
 */
class ArtifactConfig {
  
  // reference information
  
  final Pom pom
  final File file
  
  // information that may be modified

  /** The artifact group */  
  String group
  /** The artifact name */
  String name
  /** The artifact version */
  String version
  /**
   * If the artifact should be deployed (otherwise it is assumed this artifact
   * already exists somewhere, e.g. jcenter/mavenCentral)
   */
  boolean deploy
  
  // internal methods / constructor
  
  ArtifactConfig(Pom pom, File file) {
    this.pom = pom
    this.file = file
    
    group = pom.group
    name = pom.artifact
    version = pom.version
    deploy = true
  }
  
  /**
   * Apply a configuration closure.
   * 
   * @param cl the configuration closure
   */
  def call(Closure cl) {
    cl = cl.clone()
    
    cl.delegate = this
    cl.resolveStrategy = Closure.DELEGATE_FIRST
    
    cl.call()
  }
  
  // configuration accessors
  
  /**
   * @return if the artifact group, name or version was modified
   */
  boolean isModified() {
    group != pom.group || name != pom.artifact || version != pom.version
  }
  
  /**
   * Apply modifications to a Pom.
   * 
   * @param pom the Pom to adapt
   */
  void apply(Pom pom) {
    pom.group = group
    pom.artifact = name
    pom.version = version
  }
  
  /**
   * Apply modifications to a dependency.
   * 
   * @param dep the dependency to adapt
   */
  void apply(DependencyBundle dep) {
    dep.group = group
    dep.name = name
    dep.version = version
  }
  
}
