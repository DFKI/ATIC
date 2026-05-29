
package de.dfki.sds.atic.api;

/**
 *
 */
public interface ObjectCreator<T> {

    /**
     * Maps a given URI to an object.
     * @param uri
     * @return null, if uri is not a valid reference to an object
     */
    T toObject(String uri);
    
}
