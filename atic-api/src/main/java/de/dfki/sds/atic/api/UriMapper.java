
package de.dfki.sds.atic.api;

/**
 * Maps a given URI to a local file and vice versa.
 * @param <T> type
 */
public interface UriMapper<T> extends ObjectCreator<T>, UriCreator<T> {
    
}
