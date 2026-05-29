
package de.dfki.sds.atic.api;

/**
 * A URI creator decides what URI is created for an object of type {@link T}.
 */
public interface UriCreator<T> {

    /**
     * Maps a given object to a URI.
     * @param obj
     * @return 
     */
    String toURI(T obj);
    
}
