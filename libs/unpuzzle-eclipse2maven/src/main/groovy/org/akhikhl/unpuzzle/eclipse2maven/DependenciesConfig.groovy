package org.akhikhl.unpuzzle.eclipse2maven

/**
 * Configuration object for everything related to individual
 * dependencies and dependencies in general.
 */
class DependenciesConfig {
  
  // configuration properties
  
  /** Set of bundle names for bundles that should be generally excluded */
  private final Set<String> excludedBundles = new HashSet<String>()
  
  // main call
  
  def call(Closure cl) {
    cl = cl.clone()
    cl.delegate = this
    cl.resolveStrategy = Closure.DELEGATE_FIRST
    
    cl.call()
  }
  
  // configuration methods
  
  def exclude(String bundle) {
    excludedBundles << bundle
  }
  
  // configuration accessors

  boolean isExcluded(String bundle) {
    excludedBundles.contains(bundle)
  }
  
}
