/*
 * beymani-spark: Outlier and anamoly detection 
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.beymani.spark.misc

import scala.collection.mutable.ArrayBuffer
import org.apache.spark.SparkContext
import org.beymani.spark.common.OutlierUtility
import org.chombo.spark.common.GeneralUtility
import org.chombo.spark.common.JobConfiguration
import org.chombo.spark.common.Record
import org.chombo.util.BasicUtils
import org.chombo.math.MathUtils
import org.beymani.util.OutlierScoreAggregator
import org.hoidla.window.KolmogorovSminovStatWindow
import org.hoidla.window.CramerVonMisesStatWindow
import org.hoidla.window.AndersonDarlingStatWindow
import org.hoidla.window.SizeBoundStatWindow


/**
 * Window based change point detection based on 2 sample statistic
 * @author pranab
 *
 */
object ChangePointDetector extends JobConfiguration with GeneralUtility with OutlierUtility {
  
   /**
   * @param args
   * @return
   */
   def main(args: Array[String]) {
	   val appName = "inRangeBasedPredictor"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configuration params
	   val fieldDelimIn = appConfig.getString("field.delim.in")
	   val fieldDelimOut = appConfig.getString("field.delim.out")
	   val precision = getIntParamOrElse(appConfig, "output.precision", 3)
	   val keyFieldOrdinals = toOptionalIntArray(getOptionalIntListParam(appConfig, "id.fieldOrdinals"))
	   val attrOrds = BasicUtils.fromListToIntArray(getMandatoryIntListParam(appConfig, "attr.ordinals"))
	   val keyLen = getOptinalArrayLength(keyFieldOrdinals, 1)
	   val seqFieldOrd = getMandatoryIntParam(appConfig, "seq.fieldOrd", "missing seq field ordinal")
	   val windowSize = getIntParamOrElse(appConfig, "window.size", 50)
	   val statType = getStringParamOrElse(appConfig, "stat.type", "CVM")
	   val statCritValue = getMandatoryDoubleParam(appConfig, "stat.critValue", "missing stat critical value")
	   val seqCheckPoint = getOptionalIntParam(appConfig, "seq.checkPoint")
	   val debugOn = appConfig.getBoolean("debug.on")
	   val saveOutput = appConfig.getBoolean("save.output")
	   
	   //input
	   var data = sparkCntxt.textFile(inputPath)
	   
	   //filter out anything earlier than checkpoint
	   data = seqCheckPoint match {
	     case Some(seChPt) => {
	       data.filter(line => {
	         val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
	         val seq = items(seqFieldOrd).toInt
	         seq >= seChPt
	       })
	     }
	     case None => data
	   }

	   
	   //keyed data
	   val keyedData =  getKeyedValueWithSeq(data, fieldDelimIn, keyLen, keyFieldOrdinals, seqFieldOrd)

	   //check points 
	   val chPtData = keyedData.groupByKey.flatMap(v => {
	     val key = v._1
	     val keyStr = key.toString
	     val values = v._2.toList.sortBy(v => v.getLong(0))
	     val size = values.length
	     
	     attrOrds.flatMap(a => {
	       val window = createWindow(statType, windowSize)
	       val chPoints = ArrayBuffer[Long]()
	       val seqValues = ArrayBuffer[Long]()
	       
	       for (i <- 0 to (size - 1)) {
	         val v = values(i)
	         val line = v.getString(1)
	         val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
	         val quant = items(a).toDouble
	         val seq = items(seqFieldOrd).toLong
	         seqValues += seq
	         window.add(quant)
	         if (window.isProcessed()) {
	           val stat = window.getStat()
	           if (stat >= statCritValue) {
	             val seqAtCp = seqValues(i - windowSize/2)
	             chPoints += seqAtCp
	           }
	         }
	       }
	       
	       chPoints.map(c => keyStr + fieldDelimOut + a + fieldDelimOut + c)
	     })
	   })
	   
	   if (debugOn) {
         val records = chPtData.collect
         records.slice(0, 20).foreach(r => println(r))
     }
	   
	   if(saveOutput) {	   
	     chPtData.saveAsTextFile(outputPath) 
	   }	 
	   
	   
   }

   /**
	 * @param statType
	 * @param windowSize
	 * @return window
	 */
   def createWindow(statType:String, windowSize:Int) : SizeBoundStatWindow = {
     statType match {
	     case "KS" => new KolmogorovSminovStatWindow(windowSize)
	     case "CVM" => new CramerVonMisesStatWindow(windowSize)
	     case "AD" => new AndersonDarlingStatWindow(windowSize)
	   }
   }
}