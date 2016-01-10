package org.akhikhl.unpuzzle.eclipse2maven

/**
 * Configuration object for everything related to individual
 * dependencies and dependencies in general.
 */
class DependenciesConfig {
  
  class BundleConfig {
    private String bundle
    BundleConfig(String bundle) {
      this.bundle = bundle
    }
    
    def call(Closure cl) {
      cl = cl.clone()
      cl.delegate = this
      cl.resolveStrategy = Closure.DELEGATE_FIRST
      
      cl.call()
    }
    
    // bundle configuration methods
    
    /**
     * Mark this bundle to be replaced by a different bundle.
     * 
     * @param other the symbolic name of the bundle that should replace this bundle
     */
    def replaceWith(String other) {
      if (other) {
        //TODO also support version adaption?
        
        bundleReplacements[bundle] = other
      }
    }
    
  }
  
  // configuration properties
  
  /** Set of bundle names for bundles that should be generally excluded */
  protected final Set<String> excludedBundles = new HashSet<String>()
  
  /** Map of bundles to replace */
  protected final Map<String, String> bundleReplacements = [:]
  
  // main call
  
  def call(Closure cl) {
    cl = cl.clone()
    cl.delegate = this
    cl.resolveStrategy = Closure.DELEGATE_FIRST
    
    cl.call()
  }
  
  // configuration methods
  
  /**
   * Exclude a bundle from the artifacts.
   * 
   * @param bundle the bundle symbolic name
   */
  def exclude(String bundle) {
    excludedBundles << bundle
  }
  
  /**
   * Add configuration for a specific bundle.
   * 
   * @param bundle the symbolic name identifying the bundle 
   * @param config the configuration closure
   */
  def bundle(String bundle, Closure config) {
    new BundleConfig(bundle).call(config)
  }
  
  // configuration accessors

  /**
   * Determines if a given bundle is excluded.
   * 
   * @param bundle the bundle symbolic name
   * @return <code>true</code> if the bundle with the given symbolic name should be excluded,
   *   <code>false</code> otherwise
   */
  boolean isExcluded(String bundle) {
    excludedBundles.contains(bundle) || bundleReplacements.containsKey(bundle)
  }
  
  /**
   * Determines an eventual replacement bundle for a given bundle.
   *  
   * @param bundle the symbolic name of the bundle to check for a replacement
   * @return the symbolic name of the bundle that replaces the given bundle, or <code>null</code>
   */
  String getReplacement(String bundle) {
    return bundleReplacements[bundle]
  }
  
}
