/**
 * Hanlp 中文分词器 版本 1.0
 * Hanlp Analyzer Release 1.0
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 源代码由陈晨(stevenchenworking@gmail.com)提供
 * provided by Steven Chen
 */
package org.analyzer.lucene;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.common.settings.Settings;

public final class HanlpAnalyzer extends Analyzer {

	private Settings settings;

	public HanlpAnalyzer(Settings settings){
		this.settings = settings;
	}

	@Override
	protected TokenStreamComponents createComponents(String fieldName) {
		HanlpTokenizer tokenizer = new HanlpTokenizer(settings);
		return new TokenStreamComponents(tokenizer);
    }

}
