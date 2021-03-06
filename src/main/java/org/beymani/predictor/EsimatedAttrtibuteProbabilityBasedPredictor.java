/*
 * beymani: Outlier and anamoly detection 
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


package org.beymani.predictor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.beymani.util.OutlierScoreAggregator;
import org.chombo.stats.HistogramStat;
import org.chombo.util.BasicUtils;
import org.chombo.util.ConfigUtility;
import org.chombo.util.RichAttribute;
import org.chombo.util.Utility;

/**
 * Outlier detection based weighted cumulative probability of all attributes
 * @author pranab
 *
 */
public class EsimatedAttrtibuteProbabilityBasedPredictor extends DistributionBasedPredictor {
	private Map<Integer, Map<String, Integer>> attrDistr = new HashMap<Integer, Map<String, Integer>>();
	private Map<Integer, Integer> attrDistrCounts = new HashMap<Integer, Integer>();
	protected boolean requireMissingAttrValue;
	protected String scoreStrategy;
	
	/**
	 * Storm usage
	 * @param conf
	 */
	public EsimatedAttrtibuteProbabilityBasedPredictor(Map conf) {
		super(conf);
		
		//per attribute distribution
		buildAttributeWiseDistr();
		
		//attribute weights
		String[] weightStrs =  conf.get("attr.weight").toString().split(",");
		attrWeights = new double[weightStrs.length];
		for (int a = 0; a < weightStrs.length; ++a) {
			attrWeights[a] = Double.parseDouble(weightStrs[a]);
		}
		
		requireMissingAttrValue = Boolean.parseBoolean(conf.get("require.missing.attr.value").toString());
		realTimeDetection = true;
	}
	
	/**
	 * @param config
	 * @param idOrdinalsParam
	 * @param distrFilePathParam
	 * @param hdfsFileParam
	 * @param schemaFilePathParam
	 * @param attrWeightParam
	 * @param scoreThresholdParam
	 * @param seasonalParam
	 * @param fieldDelimParam
	 * @throws IOException
	 */
	public EsimatedAttrtibuteProbabilityBasedPredictor(Map<String, Object> config, String idOrdinalsParam, 
			String attrListParam, String distrFilePathParam, String hdfsFileParam, String schemaFilePathParam,String attrWeightParam, 
			 String seasonalParam, String fieldDelimParam, String scoreThresholdParam, String ignoreMissingDistrParam,
			 String scoreStrategyParam, String expConstParam, String scoreAggggregationStrtaegyParam) throws IOException {
		super(config, idOrdinalsParam,  attrListParam, distrFilePathParam, hdfsFileParam, schemaFilePathParam,  seasonalParam,  
				fieldDelimParam, scoreThresholdParam, attrWeightParam,scoreAggggregationStrtaegyParam);
			
		//attribute weights
		fieldDelim = ConfigUtility.getString(config, fieldDelimParam);
		scoreThreshold = ConfigUtility.getDouble(config, scoreThresholdParam);
		ignoreMissingDistr = ConfigUtility.getBoolean(config, ignoreMissingDistrParam);
		scoreStrategy = ConfigUtility.getString(config, scoreStrategyParam);
		expConst = ConfigUtility.getDouble(config, expConstParam);
	}
	

	/**
	 * Hadoop MR usage
	 * @param config
	 * @param distrFilePath
	 * @throws IOException
	 */
	public EsimatedAttrtibuteProbabilityBasedPredictor(Configuration config, String distrFilePathParam, String attrWeightParam, 
		String scoreThresholdParam, String fieldDelimParam) throws IOException {
		super(config, config.get(distrFilePathParam));
		
		buildAttributeWiseDistr();

		//attribute weights
		fieldDelim = config.get(fieldDelimParam, ",");
		attrWeights = Utility.doubleArrayFromString(config.get(attrWeightParam), fieldDelim);
		scoreThreshold = Double.parseDouble( config.get( scoreThresholdParam));
	}
	
	/**
	 * 
	 */
	private void buildAttributeWiseDistr() {
		//per attribute distribution
		int i = 0;
		for (RichAttribute field : schema.getFields()) {
			Integer ordinal = field.getOrdinal();
			Map<String, Integer> distr = attrDistr.get(ordinal);
			if (null == distr){
				distr = new HashMap<String, Integer>();
				attrDistr.put(ordinal, distr);
			}
			int totalCount = 0;
			for (String bucket : distrModel.keySet()) {
				String[] items = bucket.split(subFieldDelim);
				String attrBucket = items[i];
				int bucketCount = distrModel.get(bucket);
				Integer count = distr.get(attrBucket);
				if (null == count) {
					distr.put(attrBucket, bucketCount);
				} else {
					distr.put(attrBucket, count + bucketCount);
				}
				totalCount += bucketCount;
			}
			attrDistrCounts.put(ordinal, totalCount);
			++i;
		}
	}
	
	
	@Override
	public double execute(String entityID, String record) {
		String bucketKey = getBucketKey(record);
		String[] bucketElements = bucketKey.split(subFieldDelim);
		int i = 0;
		double score = 0;
		int rareCount = 0;
		for (RichAttribute field : schema.getFields()) {
			Integer ordinal = field.getOrdinal();
			String bucketElem = bucketElements[i];
			Integer count  = attrDistr.get(ordinal).get(bucketElem);
			if (null == count){
				++rareCount;
			}
			double pr = count != null ? ((double)count / attrDistrCounts.get(ordinal)) : 0;
			score += attrWeights[i] * (1.0 - pr);
			++i;
		}
		
		if (requireMissingAttrValue && rareCount == 0) {
			score = 0;
		}
		scoreAboveThreshold = score > scoreThreshold;
		if (realTimeDetection && scoreAboveThreshold) {
			//write if above threshold
			outQueue.send(entityID + " " + score);
		}

		return score;
	}

	@Override
	public double execute(String[] items, String compKey) {
		double score = 0;
		OutlierScoreAggregator scoreAggregator = new OutlierScoreAggregator(attrWeights.length, attrWeights);
		double thisScore = 0;
		for (int ord  :  attrOrdinals) {
			String keyWithFldOrd = compKey + fieldDelim + ord;
			double val = Double.parseDouble(items[ord]);
			System.out.println("keyWithFldOrd " + keyWithFldOrd);
			HistogramStat hist = keyedHist.get(keyWithFldOrd);
			if (null != hist) {
				double distr = hist.findDistr(val);
				
				if (scoreStrategy.equals("inverse")) {
					thisScore = 1.0 - distr;
				} else {
					if (distr > 0) {
						thisScore = -Math.log(distr);
					} else {
						thisScore = 20.0;
					}
				}
				scoreAggregator.addScore(thisScore);
			} else {
				BasicUtils.assertCondition(!ignoreMissingDistr, "missing distr for key " + keyWithFldOrd);
				scoreAggregator.addScore();
			}
		}
		//aggregate score	
		score = getAggregateScore(scoreAggregator);
		
		//exponential normalization
		if (expConst > 0) {
			score = BasicUtils.expScale(expConst, score);
		}
		
		scoreAboveThreshold = score > scoreThreshold;
		return score;
	}

	@Override
	public boolean isValid(String compKey) {
		String keyWithFldOrd = compKey + fieldDelim + attrOrdinals[0];
		HistogramStat hist = keyedHist.get(keyWithFldOrd);
		return null != hist;
	}
	
}
