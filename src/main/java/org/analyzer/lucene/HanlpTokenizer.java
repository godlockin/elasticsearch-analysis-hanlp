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

import com.alibaba.fastjson.JSON;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.Constants;
import org.elasticsearch.common.entity.Result;
import org.elasticsearch.common.entity.Segment;
import org.elasticsearch.common.settings.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HanlpTokenizer extends Tokenizer {

    private Logger logger = LogManager.getLogger(HanlpTokenizer.class);
    private final CharTermAttribute termAtt;
    private final OffsetAttribute offsetAtt;
    private final TypeAttribute typeAtt;
    private int endPosition;
    private Iterator<Segment> wordsIter = Collections.emptyIterator();
    private ConcurrentHashMap<String, List<String>> config;
    private RequestConfig requestConfig;

    private PositionIncrementAttribute posIncrAtt;

    private int increment = 0;

    public HanlpTokenizer() {
        super();

        offsetAtt = addAttribute(OffsetAttribute.class);
        termAtt = addAttribute(CharTermAttribute.class);
        typeAtt = addAttribute(TypeAttribute.class);
        posIncrAtt = addAttribute(PositionIncrementAttribute.class);
        config = new ConcurrentHashMap<>();
        requestConfig = RequestConfig.custom()
                .setConnectTimeout(10 * 1000)
                .setConnectionRequestTimeout(10 * 1000)
                .setSocketTimeout(60 * 1000)
                .build();
    }

    public HanlpTokenizer(Settings settings) {
        this();

        logger.info("Init QuTokenizer settings");
        config.put(Constants.URL_KEY, settings.getAsList(Constants.URL_KEY));
        config.put(Constants.TRGT_TYPES_KEY, settings.getAsList(Constants.TRGT_TYPES_KEY));
        config.put(Constants.IGNORE_POS_KEY, settings.getAsList(Constants.IGNORE_POS_KEY));

        logger.info("Config:");
        logger.info(config);
    }

    @Override
    public boolean incrementToken() {
        clearAttributes();

        if (wordsIter.hasNext()) {
            Segment token = wordsIter.next();

            String word = token.getText();
            int wordLength = word.length();
            posIncrAtt.setPositionIncrement(increment + 1);

            termAtt.append(word);
            termAtt.setLength(wordLength);

            typeAtt.setType(token.getPos());

            offsetAtt.setOffset(token.getStartIndex(), token.getEndIndex());

            endPosition = token.getEndIndex();
            return true;
        }
        return false;
    }

    @Override
    public void reset() throws IOException {
        super.reset();

        // reset the input content
        endPosition = -1;
        increment = 0;

        List<Segment> words = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(input)) {
            String fullStr = br.lines().map(String::trim).reduce("", (x, y) -> x + y).trim();

            if (isBlank(fullStr)) {
                return;
            }

            words = doQueryRemoteForSeg(fullStr);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            wordsIter = words.iterator();
        }
    }

    private List<Segment> doQueryRemoteForSeg(String fullStr) {
        SpecialPermission.check();
        return AccessController.doPrivileged((PrivilegedAction<List<Segment>>) () -> getSetUnprivileged(fullStr));
    }

    private List<Segment> getSetUnprivileged(String fullStr) {
        List<Segment> words = new ArrayList<>();

        try {
            Map<String, String> map = new HashMap() {{put("text", fullStr);}};

            HttpPost post = new HttpPost(new ArrayList<>(config.get(Constants.URL_KEY)).get(0));
            post.setConfig(requestConfig);
            post.setEntity(new StringEntity(JSON.toJSONString(map), ContentType.APPLICATION_JSON));
            post.setHeader("Content-type", "application/json");

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {

                CloseableHttpResponse response = httpClient.execute(post);
                if (200 == response.getStatusLine().getStatusCode()) {
                    HttpEntity entity = response.getEntity();

                    try (BufferedReader bfr = new BufferedReader(new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8))) {
                        String resultStr = bfr.lines().map(String::trim).reduce("", (x, y) -> x + y).trim();

                        Result result = JSON.parseObject(resultStr, Result.class);

                        Set<String> ignore = new HashSet<>(config.getOrDefault(Constants.IGNORE_POS_KEY, new ArrayList<>()));

                        result.getSegment().stream().filter(x -> !ignore.contains(x.getPos())).forEach(words::add);
                    }

                }

            } catch (IOException e) {
                e.printStackTrace();
                logger.error("Error happened during remote call," + e);
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Error happened during remote call build," + e);
        }

        return words;
    }

    @Override
    public final void end() throws IOException {
        super.end();

        // set final offset
        int finalOffset = correctOffset(this.endPosition);
        offsetAtt.setOffset(finalOffset, finalOffset);
        posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + increment);
    }

    private static boolean isBlank(CharSequence cs) {
        int strLen;
        if (cs != null && (strLen = cs.length()) != 0) {
            for (int i = 0; i < strLen; ++i) {
                if (!Character.isWhitespace(cs.charAt(i))) {
                    return false;
                }
            }

            return true;
        } else {
            return true;
        }
    }
}
