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

package gorsat.InputSources

import gorsat.Commands.CommandParseUtilities.{hasOption, stringValueOfOption}
import gorsat.Commands.{CommandArguments, CommandParseUtilities, InputSourceInfo, InputSourceParsingResult}
import gorsat.DynIterator
import gorsat.DynIterator.{DynamicNorGorSource, DynamicNorSource}
import gorsat.Iterators.{NorInputSource, ServerGorSource, ServerNorGorSource}
import gorsat.Utilities.AnalysisUtilities
import gorsat.process.{NordIterator, PipeOptions}
import org.gorpipe.gor.model.{GenomicIterator, GorOptions}
import org.gorpipe.gor.session.GorContext

import java.nio.file.{Files, Path}

object Nor
{
  def processNorArguments(context: GorContext, argString: String, iargs: Array[String],
                          args: Array[String]): InputSourceParsingResult = {

    val ignoreEmptyLines = hasOption(args, "-i")
    val forceReadHeader = hasOption(args, "-h")
    var maxWalkDepth = 1
    if (hasOption(args, "-r")) {
      maxWalkDepth = CommandParseUtilities.intValueOfOptionWithDefault(args, "-d", Int.MaxValue)
    }
    val hideModificationDate = hasOption(args, "-m")
    val followLinks = hasOption(args, "-l")

    val inputParams = iargs(0)
    var inputSource: GenomicIterator = null

    try {
      if (CommandParseUtilities.isNestedCommand(inputParams)) {
        try {
          val iteratorCommand = CommandParseUtilities.parseNestedCommand(inputParams)
          val iteratorCommandUpper = iteratorCommand.toUpperCase

          val iscmd: Boolean = if (iteratorCommandUpper.startsWith("CMD ")) {
            var k = 4
            while (k > 0 && iteratorCommandUpper.charAt(k) == '-') {
              if (iteratorCommandUpper.charAt(k + 1) == 'N') {
                k = -1
              } else {
                k = iteratorCommandUpper.indexOf(' ', k + 1) + 1
              }
            }
            if (k == -1) false else true
          } else false

          if (iteratorCommandUpper.startsWith("GOR") || iscmd) {
            inputSource = new DynamicNorGorSource(iteratorCommand, context)
          } else {
            inputSource = new DynamicNorSource(iteratorCommand, context)
          }
        } catch {
          case e: Exception => if (inputSource != null) {
            inputSource.close()
          }
            throw e
        }
      } else if (inputParams.toUpperCase.contains(".YML")) {
        val qr = context.getSession.getSystemContext.getReportBuilder.parse(iargs(0))
        val qra = Array(qr)
        val gorpipe = DynIterator.createGorIterator(context)

        val options = new PipeOptions()
        options.parseOptions(qra)
        gorpipe.processArguments(qra, executeNor = true)

        if (gorpipe.getRowSource != null) {
          inputSource = gorpipe.getRowSource
        }
      } else if (inputParams.toUpperCase.endsWith("PARQUET")) {
        var extraFilterArgs: String = if(hasOption(args, "-fs")) " -fs" else ""
        extraFilterArgs += (if(hasOption(args, "-s")) " " + stringValueOfOption(args, "-s") else "")

        if (CommandParseUtilities.hasOption(args, "-c")) {
          val cols = CommandParseUtilities.stringValueOfOption(args, "-c")
          context.setSortCols(cols)
        }

        if (hasOption(args, "-f")) {
          val tags = stringValueOfOption(args, "-f")
          inputSource = new ServerGorSource(inputParams + " -f " + tags + extraFilterArgs,  context, true)
        } else if (hasOption(args, "-ff")) {
          val tags = stringValueOfOption(args, "-ff")
          inputSource = new ServerGorSource(inputParams + " -ff " + tags + extraFilterArgs,  context, true)
        } else inputSource = new ServerGorSource(inputParams, context, true)
      } else if (inputParams.toUpperCase.endsWith("GOR") ||
        inputParams.toUpperCase.endsWith("GORZ") ||
        (inputParams.toUpperCase.endsWith("GORD") &&
          !hasOption(args, "-asdict"))) {

        var extraFilterArgs: String = if(hasOption(args, "-fs")) " -fs" else ""
        extraFilterArgs += (if(hasOption(args, "-s")) " " + stringValueOfOption(args, "-s") else "")

        if (CommandParseUtilities.hasOption(args, "-c")) {
          val cols = CommandParseUtilities.stringValueOfOption(args, "-c")
          context.setSortCols(cols)
        }

        if (hasOption(args, "-f")) {
          val tags = stringValueOfOption(args, "-f")
          inputSource = new ServerNorGorSource(inputParams + " -f " + tags + extraFilterArgs,  context, true)
        } else if (hasOption(args, "-ff")) {
          val tags = stringValueOfOption(args, "-ff")
          inputSource = new ServerNorGorSource(inputParams + " -ff " + tags + extraFilterArgs,  context, true)
        } else inputSource = new ServerNorGorSource(inputParams, context, true)
      } else if (inputParams.toUpperCase.endsWith("NORD") && !hasOption(args, "-asdict")) {
        inputSource = createNordIterator(inputParams, args, context)
      } else {
        if (inputParams.toUpperCase.endsWith("NORZ")) {
          inputSource = new ServerGorSource(inputParams, context, true)
        } else {
          var inputFile = inputParams
          if (inputParams.toUpperCase.endsWith("GORD")) {
            var dictPath = Path.of(inputParams)
            val rootPath = context.getSession.getProjectContext.getProjectRootPath
            if (!dictPath.isAbsolute() && rootPath != null) {
              dictPath = rootPath.resolve(dictPath);
            }
            if (Files.isDirectory(dictPath)) {
              inputFile = inputParams + "/" + GorOptions.DEFAULT_FOLDER_DICTIONARY_NAME
            }
          }
          inputSource = new NorInputSource(inputFile, context.getSession.getProjectContext.getFileReader, false, forceReadHeader, maxWalkDepth, followLinks, !hideModificationDate, ignoreEmptyLines)
        }
      }

      InputSourceParsingResult(inputSource, null, isNorContext = true)
    } catch {
      case e: Exception =>
        if (inputSource != null) inputSource.close()
        throw e
    }
  }

  class Nor() extends InputSourceInfo("NOR", CommandArguments("-h -asdict -r -i -m -fs", "-f -ff -s -d -c", 1, 1), isNorCommand = true) {

    override def processArguments(context: GorContext, argString: String, iargs: Array[String],
                                  args: Array[String]): InputSourceParsingResult = {
      processNorArguments(context, argString, iargs, args)
    }
  }

  class GorNor() extends InputSourceInfo("GORNOR", CommandArguments("-h -asdict -r -i -m -l -fs", "-f -ff -s -d -c", 1, 1), isNorCommand = true) {

    override def processArguments(context: GorContext, argString: String, iargs: Array[String],
                                  args: Array[String]): InputSourceParsingResult = {
      processNorArguments(context, argString, iargs, args)
    }
  }

  def createNordIterator(fileName: String, args: Array[String], context: GorContext): GenomicIterator = {
    val hasFileFilter = CommandParseUtilities.hasOption(args, "-ff")
    val hasFilter = CommandParseUtilities.hasOption(args, "-f")
    val tags = AnalysisUtilities.getFilterTags(args, context, doHeader = false).split(',').filter(x => x.nonEmpty)
    val sourceColumnName = CommandParseUtilities.stringValueOfOptionWithDefault(args, "-s", "")
    val iterator =  new NordIterator(fileName, hasFileFilter | hasFilter, tags, sourceColumnName, hasOption(args, "-fs"), hasOption(args, "-h"))
    iterator.init(context.getSession)
    iterator
  }

}
