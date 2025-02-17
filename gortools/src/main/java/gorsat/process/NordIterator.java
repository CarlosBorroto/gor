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

package gorsat.process;

import gorsat.Iterators.NorInputSource;
import org.gorpipe.exceptions.GorDataException;
import org.gorpipe.exceptions.GorParsingException;
import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.model.GenomicIterator;
import org.gorpipe.gor.model.GenomicIteratorBase;
import org.gorpipe.gor.session.GorSession;
import org.gorpipe.gor.table.Dictionary;
import org.gorpipe.gor.model.FileReader;
import org.gorpipe.gor.model.Row;
import org.gorpipe.gor.util.DataUtil;
import org.gorpipe.model.gor.RowObj;
import org.gorpipe.gor.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>Iterator to access data from a nor dictionary file.
 *
 * <p>Nor dictionary file is a file to tag mapping where the iterator represents the data as a continuous stream. The nor
 * dictionary iterator supports filtering from an input filter array. An empty filter array indicates no filter. Results
 * from the nor dictionary iterator contains the original source plus a source column which contains the originating tag
 * for the row. Filters can be silenced and a custom source column name can be applied. All files in the nor dictionary
 * are relative to the dictionary file unless a full path is specified.
 *
 * <p>Nor dictionary supports header which starts with #. All lines starting with # are ignored from the dictionary. All
 * header entries starting with ##[key]=[value] are treated as key value pairs. Nord files support the 'Source' keyword
 * for naming the source column. If no Source reference is set in the nor dictionary file or no source is set with the
 * -s option, the source column is not shown.
 *
 * <p>Example of a nor dictionary:
 *
 *
 * <p>##Source=phenotype
 * <p>#Source\tTag\n
 * <p>./nor/file1.tsv\tPatient_1\n
 * <p>./nor/file2.tsv\tPatient_2\n
 * <p>./nor/file3.tsv\tPatient_3\n
 * <p>#./nor/file4.tsv\tPatient_4\n   -&gt; Ignored
 *
 * <p>Nor dictionaries do not support bucketization as there only one file open at a time.
 *
 */
public class NordIterator extends GenomicIteratorBase {

    private final NordFile nordFile;
    private String sourceColumnName;
    private String projectRoot = "";
    private FileReader fileReader;
    private final boolean ignoreMissingEntries;
    private final boolean forceReadOfHeader;
    private boolean addSourceColumn;
    private boolean showSourceColumn = true;
    private GenomicIterator activeIterator;
    private Iterator<NordIteratorEntry> nordEntriesIterator;
    private NordIteratorEntry activeEntry = null;
    private String nordRoot;

    private static final String DEFAULT_SOURCE_COLUMN_NAME = "Source";
    private static final String SOURCE_PROPERTY_NAME = "Source";
    private GorSession gorSession;
    private final Set<String> nestedIterators;

    /**
     * Nor dictionary constructor. Creates an instance of nor dictionary iterator, see class description.
     *
     * @param nordFile              Nor dictionary file object
     * @param sourceColumnName      Name of the ouput source column
     * @param ignoreMissingEntries  Indicates if missing entries should be ignored or not. Not ignoring missing entries
     *                              will throw a parsing exception.
     * @param forceReadOfHeader     Force the read of headers in the source files.
     */
    public NordIterator(NordFile nordFile,
                        String sourceColumnName,
                        boolean ignoreMissingEntries,
                        boolean forceReadOfHeader) {
        this(nordFile, sourceColumnName, ignoreMissingEntries, forceReadOfHeader, new HashSet<>());
    }

    private NordIterator(NordFile nordFile,
                        String sourceColumnName,
                        boolean ignoreMissingEntries,
                        boolean forceReadOfHeader,
                        Set<String> nestedIterators) {
        this.nordFile = nordFile;
        this.ignoreMissingEntries = ignoreMissingEntries;
        this.forceReadOfHeader = forceReadOfHeader;
        this.sourceColumnName = sourceColumnName;
        this.nestedIterators = nestedIterators;
    }

    @Override
    public void close() {
        if (activeIterator != null) {
            activeIterator.close();
            activeIterator = null;
        }
    }

    @Override
    public boolean hasNext() {
        while (activeIterator == null || !activeIterator.hasNext()) {
            // Note that we do this in a loop to handle potentially empty files in the middle of the dict
            if(!prepareNextIterator()) {
                return false;
            }
        }

        return activeIterator != null && activeIterator.hasNext();
    }

    @Override
    public Row next() {
        if (activeIterator != null && activeIterator.hasNext()) {
             String extraColumn = "";
             if (showSourceColumn && addSourceColumn) extraColumn = "\t" + activeEntry.tag();

             return RowObj.StoR(activeIterator.next() + extraColumn);
        }

        return null;
    }

    @Override
    public boolean seek(String seekChr, int seekPos) {
        throw new GorSystemException("Nor dictionary iterator does not support seek", null);
    }

    public void init(GorSession session) {

        gorSession = session;
        this.fileReader = gorSession.getProjectContext().getFileReader();
        this.projectRoot = gorSession.getProjectContext().getRealProjectRoot();

        if (nordFile.fileName().getParent() == null) {
            nordRoot = ".";
        } else {
            nordRoot = nordFile.fileName().getParent().toString();
        }

        addToNested(nordFile.fileName().toString());

        if (StringUtil.isEmpty(this.sourceColumnName)) {
            if (nordFile.properties().containsKey(SOURCE_PROPERTY_NAME)) {
                this.sourceColumnName = nordFile.properties().get(SOURCE_PROPERTY_NAME);
            } else {
                showSourceColumn = false;
                this.sourceColumnName = DEFAULT_SOURCE_COLUMN_NAME;
            }
        }

        nordEntriesIterator = nordFile.entries().iterator();

        if(!nordEntriesIterator.hasNext()) {
            getHeaderFromFirstFile();
        }
        prepareNextIterator();
    }

    private void addToNested(String filename) {
        try {
            File file = new File(filename);
            String canonicalPath = file.getCanonicalPath();
            if (!nestedIterators.contains(canonicalPath)) {
                nestedIterators.add(canonicalPath);
            } else {
                String message = String.format("Recursion detected in nested nor dictionary: %s", filename);
                throw new GorDataException(message);
            }
        } catch (IOException e) {
            // Don't care
        }
    }

    private void getHeaderFromFirstFile() {
        if (nordFile.firstEntry() == null) {
            return;
        }

        var entry = nordFile.firstEntry();
        var entryPath = Path.of(entry.filePath());

        if (!entryPath.isAbsolute()) {
            entryPath = Paths.get(this.nordRoot, entryPath.toString());
        }

        try (NorInputSource inputSource = new NorInputSource(entryPath.toString(),
                this.fileReader, false, this.forceReadOfHeader, 0, false, false, true)) {
            getHeaderFromIterator(inputSource);
        }
    }

    private boolean prepareNextIterator() {
        // Close the active file iterator
        close();

        if (nordEntriesIterator.hasNext()) {

            // Read the next nord entry
            activeEntry = nordEntriesIterator.next();

            // Get the file path from entry
            String fileName = activeEntry.filePath();
            Path entryPath = Paths.get(fileName);

            if (!entryPath.isAbsolute()) {
                Path path = Paths.get(this.projectRoot).resolve(this.nordFile.fileName());
                Dictionary.FileReference reference = Dictionary.getDictionaryFileParent(path.toString(), this.projectRoot);
                Dictionary.DictionaryLine line = Dictionary.parseDictionaryLine(activeEntry.toString(), reference, this.nordFile.fileName().toString());

                if (reference.logical != null)
                    fileName = line.fileRef.logical;
                else
                    fileName = Paths.get(this.nordRoot, fileName).toString();
            }

            if (DataUtil.isNord(fileName)) {
                var newNordFile = new NordFile();
                newNordFile.load(this.fileReader, Path.of(fileName), this.nordFile.useFilter(), this.nordFile.filterEntries(),
                        this.nordFile.ignoreMissing());
                activeIterator = new NordIterator(newNordFile, "", ignoreMissingEntries, forceReadOfHeader, nestedIterators);
            } else {
                activeIterator = new NorInputSource(fileName, this.fileReader, false, this.forceReadOfHeader, 0, false, false, true);
            }
            activeIterator.init(gorSession);

            // Test header
            try {
                getHeaderFromIterator(this.activeIterator);
            } catch (Exception e) {
                close();
                throw e;
            }
            return true;
        } else {
            return false;
        }
    }

    private void getHeaderFromIterator(GenomicIterator inputSource) {
        String iteratorHeader = inputSource.getHeader();
        if(iteratorHeader.isEmpty()) {
            throw new GorDataException("Missing header for: " + activeEntry.tag());
        }
        iteratorHeader = addOptionalSourceColumn(iteratorHeader);
        String expectedHeader = getHeader();

        if (getHeader().isEmpty()) {
            setHeader(iteratorHeader);
        } else if (!iteratorHeader.equalsIgnoreCase(expectedHeader)) {
            String message = String.format("Headers do not match between dictionary files for: %s\n" +
                    "Expected header: %s\n" +
                    "     Got header: %s", activeEntry.tag(), expectedHeader, iteratorHeader);
            throw new GorDataException(message);
        }
    }

    private String addOptionalSourceColumn(String iteratorHeader) {
        this.addSourceColumn = false;
        if (!iteratorHeader.contains("\t" + this.sourceColumnName)) {
            if (showSourceColumn) iteratorHeader += "\t" + this.sourceColumnName;
            this.addSourceColumn = true;
        }
        return iteratorHeader;
    }
}
