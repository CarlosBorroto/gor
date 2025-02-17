package org.gorpipe.gor.table.dictionary;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.gorpipe.gor.model.FileReader;
import org.gorpipe.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;

public class DictionaryCache {

    private static final Logger log = LoggerFactory.getLogger(DictionaryTable.class);

    public static final boolean useCache = Boolean.parseBoolean(System.getProperty("gor.dictionary.cache.active", "true"));

    final private static Cache<String, DictionaryTable> dictCache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterAccess(Duration.ofHours(12L))
            .softValues()
            .build();   //A map from dictionaries to the cache objects.


    public static DictionaryTable getOrCreateTable(String path, FileReader fileReader) throws IOException {
        return getOrCreateTable(path, fileReader, useCache);
    }

    public static DictionaryTable getOrCreateTable(String path, FileReader fileReader, boolean useCache) throws IOException {
        try {
            return getTable(path, fileReader, useCache);
        } catch (NoSuchFileException e) {
            return new DictionaryTable(path, fileReader);
        }
    }

    public synchronized static DictionaryTable getTable(String path, FileReader fileReader) throws IOException {
        return getTable(path, fileReader, useCache);
    }

    public synchronized static DictionaryTable getTable(String path, FileReader fileReader, boolean useCache) throws IOException {
        // TODO:  To make fewer calls to exists consider caching it in metadata.  Should not need this as getSourceMetaData should throw
        //        exception if file does not exists.
        if (!fileReader.exists(path)) {
            throw new NoSuchFileException(path);
        }
        // The dict is lazy loaded so the only cost is finding the id.
        DictionaryTable dict = new DictionaryTable(path, fileReader);

        if (useCache) {
            String uniqueID = dict.getId();
            var key = dictCacheKeyFromPathAndRoot(path, fileReader);
            if (Strings.isNullOrEmpty(uniqueID)) {
                dictCache.invalidate(key);
                return dict;
            } else {
                DictionaryTable dictFromCache = dictCache.getIfPresent(key);
                if (dictFromCache == null || !dictFromCache.getId().equals(uniqueID)) {
                    dictCache.put(key, dict);
                    return dict;
                } else {
                    return dictFromCache;
                }
            }
        } else {
            return dict;
        }
    }

    public synchronized static void updateCache(DictionaryTable table) {
        String uniqueID = table.getId();
        var key = dictCacheKeyFromPathAndRoot(table.getPath(), table.getFileReader());
        if (uniqueID == null || uniqueID.equals("")) {
            log.warn("Trying to put table with non unique id ({}) into cache", uniqueID);
        } else {
            dictCache.put(key, table);
        }
    }

    private static String dictCacheKeyFromPathAndRoot(String path, FileReader fileReader) {
        return fileReader.resolveUrl(path).getFullPath();
    }
}
