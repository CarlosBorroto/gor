/*
 *  BEGIN_COPYRIGHT
 *
 *  Copyright (C) 2011-2013 deCODE genetics Inc.
 *  Copyright (C) 2013-2019 WuXi NextCode Inc.
 *  All Rights Reserved.
 *
 *  GORpipe is free software: you can redistribute it and/or modify
 *  it under the terms of the AFFERO GNU General Public License as published by
 *  the Free Software Foundation.
 *
 *  GORpipe is distributed "AS-IS" AND WITHOUT ANY WARRANTY OF ANY KIND,
 *  INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
 *  NON-INFRINGEMENT, OR FITNESS FOR A PARTICULAR PURPOSE. See
 *  the AFFERO GNU General Public License for the complete license terms.
 *
 *  You should have received a copy of the AFFERO GNU General Public License
 *  along with GORpipe.  If not, see <http://www.gnu.org/licenses/agpl-3.0.html>
 *
 *  END_COPYRIGHT
 */

package org.gorpipe.gor.manager;

import org.apache.commons.lang3.ArrayUtils;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.model.FileReader;
import org.gorpipe.gor.table.dictionary.DictionaryTable;
import org.gorpipe.gor.table.dictionary.DictionaryEntry;
import org.gorpipe.gor.table.dictionary.TableFilter;
import org.gorpipe.gor.table.lock.ExclusiveFileTableLock;
import org.gorpipe.gor.table.lock.TableLock;
import org.gorpipe.gor.table.lock.TableTransaction;
import org.gorpipe.gor.util.DataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Class to manage gor tables (dictionaries and tables).
 * <p>
 * Includes commands to:
 * <p>
 * load table
 * insert entries into table
 * delete entries from table
 * select
 * bucketize
 * remove entries from buckets
 * delete buckets
 * <p>
 * Created by gisli on 18/08/16.
 * <p>
 * TODO: Why not move bucketize methods into the DictionaryTable object.
 *
 * TODO:   bucketzationInfo should be part of the table and stored in the table header (only passed in when table is created).
 *         Use sensible defaults otherwise.
 */
public class TableManager {

    private static final Logger log = LoggerFactory.getLogger(TableManager.class);

    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(30);
    public static final Class<? extends TableLock> DEFAULT_LOCK_TYPE = ExclusiveFileTableLock.class;

    // Members

    // TODO:  This is passed on to the tables, so ALL the tables must share the same security context so the tm can
    // not be used for different projects.
    private FileReader fileReader;
    private Class<? extends TableLock> lockType = DEFAULT_LOCK_TYPE;
    private Duration lockTimeout = DEFAULT_LOCK_TIMEOUT;

    // TODO:  Store these as part of the table?  And persist in table props.   Can also be stored here as default for new
    //       tabales but the bucketization should use hte one from the table.
    private int minBucketSize = -1;
    private int bucketSize = -1;

    /**
     * Default constructor.
     */
    public TableManager() {
    }

    private TableManager(Builder builder) {
        this.lockType = inferLockType(builder.lockType);
        this.lockTimeout = builder.lockTimeout != null ? builder.lockTimeout : DEFAULT_LOCK_TIMEOUT;

        // Must set bucket size first as it is the main size value..
        if (builder.bucketSize > 0) {
            setBucketSize(builder.bucketSize);
        }
        if (builder.minBucketSize > 0) {
            setMinBucketSize(builder.minBucketSize);
        }
        this.fileReader = builder.fileReader;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public void setMinBucketSize(int minBucketSize) {
        this.minBucketSize = minBucketSize;
    }

    public int getBucketSize() {
        return bucketSize;
    }

    /**
     * Set the bucket size.  Also updates the minBucketSize if it is larger than {@code bucketSize}.
     *
     * @param bucketSize the requested bucket size.
     */
    public void setBucketSize(int bucketSize) {
        this.bucketSize = bucketSize;
    }

    public FileReader getfileReader() {
        return fileReader;
    }

    public void setfileReader(FileReader fileReader) {
        this.fileReader = fileReader;
    }

    public Class<? extends TableLock> getLockType() {
        return lockType;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    /**
     * Initialize the table given by {@code path}.
     *
     * @param path path to the table.
     * @return the table given by {@code path}.
     */
    public DictionaryTable initTable(String path) {
        if (DataUtil.isGord(path)) {
            return new DictionaryTable.Builder<>(path).fileReader(fileReader).build();
        } else {
            throw new RuntimeException("BaseTable of type " + path + " are not supported!");
        }
    }

    public DictionaryTable initTable(Path path) {
        return initTable(path.toString());
    }

    /**
     * Insert new data file (partition) into the table. This might trigger bucketization.
     *
     * @param tableFile the path to the table file.
     * @param packLevel pack level to use (see BucketPackLevel).
     * @param workers   number of workers to use for bucketization (if needed).
     * @param entries   Files/lines to insert.
     */
    public void insert(String tableFile, BucketManager.BucketPackLevel packLevel, int workers, DictionaryEntry... entries) {
        DictionaryTable table = initTable(tableFile);
        insert(table, packLevel, workers, entries);
    }

    /**
     * Insert new data file (partition) into the table. This might trigger bucketization.
     *
     * @param table     the table to insert into.
     * @param packLevel pack level to use (see BucketPackLevel).
     * @param workers   number of workers to use for bucketization (if needed).
     * @param entries   Files/lines to insert.
     */
    public void insert(DictionaryTable table, BucketManager.BucketPackLevel packLevel, int workers, DictionaryEntry... entries) {
        try (TableTransaction trans = TableTransaction.openWriteTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            table.insert(entries);
            trans.commit();
        }
    }

    /**
     * Save the given table.
     *
     * @param table     the table to save.
     * @param packLevel pack level to use (see BucketPackLevel).
     * @param workers   number of workers to use for bucketization (if needed).
     */
    public void save(DictionaryTable table, BucketManager.BucketPackLevel packLevel, int workers) {
        try (TableTransaction trans = TableTransaction.openWriteTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            trans.commit();
        }
    }

    /**
     * Delete entries from the table.
     *
     * @param tableFile path to the table file.
     * @param entries   the entries to delete.
     */
    public void delete(String tableFile, DictionaryEntry... entries) {
        System.err.println("Deleting entries: " + entries.length);
        DictionaryTable table = initTable(tableFile);
        try (TableTransaction trans = TableTransaction.openWriteTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            table.delete(entries);
            trans.commit();
        }
    }

    /**
     * Delete entries from the table.
     *
     * @param tableFile path to the table file.
     * @param entries   the entries to delete.
     */
    public void delete(String tableFile, TableFilter entries) {
        System.err.println("Deleting entries2: " + entries);
        DictionaryTable table = initTable(tableFile);
        try (TableTransaction trans = TableTransaction.openWriteTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            table.delete(entries.get());
            trans.commit();
        }
    }

    /**
     * Select entries from the table.
     * <p>
     * This function is useful to select entries as input into other methods of this class.
     *
     * @param tableFile       path to the table file.
     * @param files           list of files to include.
     * @param aliases         list of aliases to include.
     * @param tags            list of tags to include.
     * @param buckets         list of buckets to include.
     * @param chrRange        filter range, string in format [chr from]:[pos from]-[chr to]:[pos to].
     *                        Example: chr1:10000-chr1:200000
     * @param includedDeleted Should deleted files be included in the result.
     * @return entries from the table, matching the given criteria.
     */
    public List<? extends DictionaryEntry> select(String tableFile, String[] files, String[] aliases, String[] tags, String[] buckets, String chrRange, boolean includedDeleted) {
        DictionaryTable table = initTable(tableFile);
        return table.filter()
                .files(files)
                .aliases(aliases)
                .tags(ArrayUtils.addAll(tags))
                .buckets(buckets)
                .chrRange(chrRange)
                .includeDeleted(includedDeleted).get();
    }

    /**
     * Select all entries from table.
     * <p>
     * This function is useful to select entries as input into other methods of this class.
     *
     * @param tableFile path to the table file.
     * @return all entries from table as a collection.
     */
    public Collection<? extends DictionaryEntry> selectAll(String tableFile) {
        DictionaryTable table = initTable(tableFile);
        try (TableTransaction trans = TableTransaction.openReadTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            return table.selectAll();
        }
    }

    public void print(TableFilter lines) {
        DictionaryTable table = lines.getTable();
        try (TableTransaction trans = TableTransaction.openReadTransaction(this.lockType, table, table.getName(), this.lockTimeout)) {
            for (Object line : lines.get()) {
                System.out.print(((DictionaryEntry) line).formatEntry());
            }
        }
    }

    /**
     * Bucketize the given table.
     *
     * @param tableFile      the path to the table file.
     * @param packLevel      pack level to use (see BucketPackLevel).
     * @param workers        number of workers to use for bucketization (if needed).
     * @param maxBucketCount Maximum number of buckets to generate on this call.
     * @param bucketDirs     array of directories to bucketize to, ignored if null.  The dirs are absolute or relative to the table dir.
     */
    public void bucketize(String tableFile, BucketManager.BucketPackLevel packLevel, int workers, int maxBucketCount, List<String> bucketDirs) {
        DictionaryTable table = initTable(tableFile);
        BucketManager.newBuilder(table)
                .lockTimeout(this.lockTimeout)
                .bucketSize(this.bucketSize)
                .minBucketSize(this.minBucketSize)
                .lockType(this.lockType)
                .bucketCreator(new BucketCreatorGorPipe(workers))
                .build()
                .bucketize(packLevel, maxBucketCount, bucketDirs, false);
    }

    /**
     * Delete the given buckets.
     *
     * @param table     table to update.
     * @param force     force clean up (ignore grace periods).
     * @param buckets   list of buckets to be deleted.
     */
    public void deleteBuckets(DictionaryTable table, boolean force, String... buckets) {
        BucketManager.newBuilder(table)
                .lockTimeout(this.lockTimeout)
                .bucketSize(this.bucketSize)
                .minBucketSize(this.minBucketSize)
                .lockType(this.lockType)
                .build()
                .deleteBuckets(force, buckets);
    }

    /**
     * Delete the given buckets.
     *
     * @param tableFile the path to the table file.
     * @param buckets   list of buckets to be deleted.
     */
    public void deleteBuckets(String tableFile, String... buckets) {
        DictionaryTable table = initTable(tableFile);
        deleteBuckets(table, false, buckets);
    }

    private Class<? extends TableLock> inferLockType(Class<? extends TableLock> newLockType) {
        Class<? extends TableLock> inferredLockType = DEFAULT_LOCK_TYPE;

        if (newLockType != null) {
            inferredLockType = newLockType;
            log.debug("Setting lock type from builder.");
        } else {

            String lockClassName = System.getProperty("gor.manager.locktype.class");
            if (lockClassName != null && lockClassName.length() > 0) {
                try {
                    Class<?> cls = Class.forName(lockClassName);
                    if (cls != null) {
                        inferredLockType = cls.asSubclass(TableLock.class);
                        log.debug("Setting lock type from config {}", "gor.manager.locktype.class");
                    }
                } catch (ClassNotFoundException e) {
                    throw new GorSystemException(e);
                }
            }
        }
        log.debug("Using {} for locking.", inferredLockType);
        return inferredLockType;
    }

    public static final class Builder {
        private Duration lockTimeout = null;
        private Class<? extends TableLock> lockType = null;
        private int minBucketSize = -1;
        private int bucketSize = -1;
        private FileReader fileReader;

        private Builder() {
        }

        public Builder lockType(Class val) {
            lockType = val;
            return this;
        }

        public Builder lockTimeout(Duration val) {
            lockTimeout = val;
            return this;
        }

        public Builder minBucketSize(int val) {
            minBucketSize = val;
            return this;
        }

        public Builder bucketSize(int val) {
            bucketSize = val;
            return this;
        }

        public Builder fileReader(FileReader val) {
            fileReader = val;
            return this;
        }

        public TableManager build() {
            return new TableManager(this);
        }
    }
}
