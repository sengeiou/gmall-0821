package com.atguigu.gmall.search.service;

import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.pms.entity.BrandEntity;
import com.atguigu.gmall.pms.entity.CategoryEntity;
import com.atguigu.gmall.search.pojo.Goods;
import com.atguigu.gmall.search.pojo.SearchParamVo;
import com.atguigu.gmall.search.pojo.SearchResponseAttrVo;
import com.atguigu.gmall.search.pojo.SearchResponseVo;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.nested.ParsedNested;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedLongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SearchService {

    // ???????????????
    @Autowired
    private RestHighLevelClient restHighLevelClient;

    public SearchResponseVo search(SearchParamVo paramVo) {
        try {
            SearchRequest searchRequest = new SearchRequest(new String[]{"goods"}, this.buildDsl(paramVo));
            SearchResponse response = this.restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);

            SearchResponseVo responseVo = this.parseResult(response);
            // ???????????????????????????????????? (??????????????????????????????????????????)
            responseVo.setPageNum(paramVo.getPageNum());
            responseVo.setPageSize(paramVo.getPageSize());
            return responseVo;
        } catch (IOException e) {
            e.printStackTrace();
        }


        return null;
    }


    private SearchResponseVo parseResult(SearchResponse response) {
        SearchResponseVo responseVo = new SearchResponseVo();

        // ??????hits ????????????????????????????????????????????????
        SearchHits hits = response.getHits();
        // ??????????????????
        responseVo.setTotal(hits.totalHits);

        // ???????????????????????????
        SearchHit[] hitsHits = hits.getHits();
        List<Goods> goodsList = Stream.of(hitsHits).map(hitsHit -> {
            String json = hitsHit.getSourceAsString();
            // ???_source???????????????goods??????
            Goods goods = JSON.parseObject(json, Goods.class);

            // ????????????????????????????????????title
            Map<String, HighlightField> highlightFields = hitsHit.getHighlightFields();
            HighlightField highlightField = highlightFields.get("title");
            Text[] fragments = highlightField.getFragments();
            goods.setTitle(fragments[0].string());

            return goods;
        }).collect(Collectors.toList());
        responseVo.setGoodsList(goodsList);

        // 2. ????????????????????????????????????????????????map???????????? (??????aggregations??? ?????????????????????????????????????????????????????????)
        Map<String, Aggregation> aggregationMap = response.getAggregations().asMap();

        // 2.1. ????????????id?????????
        ParsedLongTerms brandIdAgg = (ParsedLongTerms) aggregationMap.get("brandIdAgg");
        List<? extends Terms.Bucket> brandBuckets = brandIdAgg.getBuckets();
        if (!CollectionUtils.isEmpty(brandBuckets)) {
            responseVo.setBrands(brandBuckets.stream().map(bucket -> {
                BrandEntity brandEntity = new BrandEntity();
                // ????????????key???????????????id
                brandEntity.setId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());
                // ????????????id????????? ????????????????????? ??????logo????????????
                Map<String, Aggregation> subAggregationMap = ((Terms.Bucket) bucket).getAggregations().asMap();
                // ?????????????????????????????????????????????
                ParsedStringTerms brandNameAgg = (ParsedStringTerms) subAggregationMap.get("brandNameAgg");
                List<? extends Terms.Bucket> nameAggBuckets = brandNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameAggBuckets)) {
                    brandEntity.setName(nameAggBuckets.get(0).getKeyAsString());
                }
                // ????????????logo?????????????????????logo
                ParsedStringTerms logoAgg = (ParsedStringTerms) subAggregationMap.get("logoAgg");
                List<? extends Terms.Bucket> logoBuckets = logoAgg.getBuckets();
                if (!CollectionUtils.isEmpty(logoBuckets)) {
                    brandEntity.setLogo(logoBuckets.get(0).getKeyAsString());
                }
                return brandEntity;
            }).collect(Collectors.toList()));
        }

        // 2.2 ??????????????????
        ParsedLongTerms categoryIdAgg = (ParsedLongTerms) aggregationMap.get("categoryIdAgg");
        List<? extends Terms.Bucket> categoryBuckets = categoryIdAgg.getBuckets();
        if (!CollectionUtils.isEmpty(categoryBuckets)) {
            // ?????????????????????????????????
            responseVo.setCategories(categoryBuckets.stream().map(bucket -> {
                CategoryEntity categoryEntity = new CategoryEntity();
                categoryEntity.setId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());
                // ????????????????????????????????????????????????
                ParsedStringTerms categoryNameAgg = ((Terms.Bucket) bucket).getAggregations().get("categoryNameAgg");
                List<? extends Terms.Bucket> nameAggBuckets = categoryNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameAggBuckets)) {
                    categoryEntity.setName(nameAggBuckets.get(0).getKeyAsString());
                }
                return categoryEntity;
            }).collect(Collectors.toList()));
        }

        // 2.3 ????????????????????????????????????????????????????????????
        ParsedNested attrAgg = (ParsedNested) aggregationMap.get("attrAgg");
        // ??????????????????????????????????????????attrId????????????
        ParsedLongTerms attrIdAgg = (ParsedLongTerms) attrAgg.getAggregations().get("attrIdAgg");
        // ??????attrId??????????????????????????????????????????????????????????????????
        List<? extends Terms.Bucket> attrIdAggBuckets = attrIdAgg.getBuckets();
        // ???????????????????????????????????????????????????????????????????????????
        if (!CollectionUtils.isEmpty(attrIdAggBuckets)) {

            responseVo.setFilters(attrIdAggBuckets.stream().map(bucket -> {
                // ?????????????????????SearchResponseAttrVo??????
                SearchResponseAttrVo responseAttrVo = new SearchResponseAttrVo();
                // ???????????????key?????????attrId
                responseAttrVo.setAttrId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());
                // ??????attrIdAgg???????????????attrNameAgg attrValueAgg
                Map<String, Aggregation> subAggregationMap = ((Terms.Bucket) bucket).getAggregations().asMap();
                // ???????????????????????????????????????????????????????????????
                ParsedStringTerms attrNameAgg = (ParsedStringTerms) subAggregationMap.get("attrNameAgg");
                List<? extends Terms.Bucket> nameAggBuckets = attrNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameAggBuckets)) {
                    responseAttrVo.setAttrName(nameAggBuckets.get(0).getKeyAsString());
                }

                // ?????????????????????????????????????????????????????????????????????
                ParsedStringTerms attrValueAgg = (ParsedStringTerms) subAggregationMap.get("attrValueAgg");
                List<? extends Terms.Bucket> buckets = attrValueAgg.getBuckets();
                if (!CollectionUtils.isEmpty(buckets)) {
                    responseAttrVo.setAttrValues(buckets.stream().map(Terms.Bucket::getKeyAsString).collect(Collectors.toList()));
                }
                return responseAttrVo;
            }).collect(Collectors.toList()));
        }

        return responseVo;
    }


    private SearchSourceBuilder buildDsl(SearchParamVo paramVo) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        String keyword = paramVo.getKeyword();
        if (StringUtils.isBlank(keyword)) {
            // TODO????????????
            return sourceBuilder;
        }

        // 1. ???????????????????????????
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        sourceBuilder.query(boolQueryBuilder);

        // 1.1. ??????????????????
        boolQueryBuilder.must(QueryBuilders.matchQuery("title", keyword).operator(Operator.AND));

        // 1.2. ??????????????????
        // 1.2.1. ????????????
        List<Long> brandId = paramVo.getBrandId();
        if (!CollectionUtils.isEmpty(brandId)) {
            boolQueryBuilder.filter(QueryBuilders.termsQuery("brandId", brandId));
        }

        // 1.2.2. ????????????
        List<Long> categoryId = paramVo.getCategoryId();
        if (!CollectionUtils.isEmpty(categoryId)) {
            boolQueryBuilder.filter(QueryBuilders.termsQuery("categoryId", categoryId));
        }

        // 1.2.3. ??????????????????
        Double priceFrom = paramVo.getPriceFrom();
        Double priceTo = paramVo.getPriceTo();
        if (priceFrom != null || priceTo != null) {
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("price");
            if (priceFrom != null) {
                rangeQuery.gte(priceFrom);
            }
            if (priceTo != null) {
                rangeQuery.lte(priceTo);
            }
            boolQueryBuilder.filter(rangeQuery);
        }

        // 1.2.4. ????????????
        Boolean store = paramVo.getStore();
        if (store != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("store", store));
        }

        // 1.2.5. ??????????????????
        List<String> props = paramVo.getProps();
        if (!CollectionUtils.isEmpty(props)) {
            props.forEach(prop -> { // 4:8G-12G
                // ?????????????????? attrId???attrValues?????????
                String[] attr = StringUtils.split(prop, ":");
                if (attr != null && attr.length == 2) {
                    BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
                    // ????????????id???????????????????????????????????????????????????attrId???
                    boolQuery.must(QueryBuilders.termQuery("searchAtts.attrId", attr[0]));
                    // ???????????????????????????????????? ???????????????????????????8G-12G???
                    String[] attrValues = StringUtils.split(attr[1], "-");
                    boolQuery.must(QueryBuilders.termsQuery("searchAtts.attrValue", attrValues));
                    // ?????????prop?????????????????????????????????1-????????????????????????path 2-??????????????????query 3-????????????
                    boolQueryBuilder.filter(QueryBuilders.nestedQuery("searchAtts", boolQuery, ScoreMode.None));
                }
            });
        }

        // 2. ??????
        Integer sort = paramVo.getSort();
        if (sort != null) {
            switch (sort) {
                case 1:
                    sourceBuilder.sort("price", SortOrder.DESC);
                    break;
                case 2:
                    sourceBuilder.sort("price", SortOrder.ASC);
                    break;
                case 3:
                    sourceBuilder.sort("sales", SortOrder.DESC);
                    break;
                case 4:
                    sourceBuilder.sort("createTime", SortOrder.DESC);
                    break;
                default:
                    sourceBuilder.sort("_score", SortOrder.DESC);
                    break;
            }
        }

        // 3. ??????
        Integer pageNum = paramVo.getPageNum();
        Integer pageSize = paramVo.getPageSize();
        sourceBuilder.from((pageNum - 1) * pageSize);
        sourceBuilder.size(pageSize);

        // 4. ??????
        sourceBuilder.highlighter(
                new HighlightBuilder()
                        .field("title")
                        .preTags("<font style='color:red'>")
                        .postTags("</font>")
        );

        // 5. ??????
        // 5.1. ????????????
        sourceBuilder.aggregation(
                AggregationBuilders.terms("brandIdAgg").field("brandId")
                        .subAggregation(AggregationBuilders.terms("brandNameAgg").field("brandName"))
                        .subAggregation(AggregationBuilders.terms("logoAgg").field("logo"))
        );

        // 5.2. ????????????
        sourceBuilder.aggregation(AggregationBuilders.terms("categoryIdAgg").field("categoryId")
                .subAggregation(AggregationBuilders.terms("categoryNameAgg").field("categoryName")));

        // 5.3. ???????????????????????????
        sourceBuilder.aggregation(
                AggregationBuilders.nested("attrAgg", "searchAtts")
                        .subAggregation(AggregationBuilders.terms("attrIdAgg").field("searchAtts.attrId")
                        .subAggregation(AggregationBuilders.terms("attrNameAgg").field("searchAtts.attrName"))
                        .subAggregation(AggregationBuilders.terms("attrValueAgg").field("searchAtts.attrValue")))
        );

        // 6. ???????????????
        sourceBuilder.fetchSource(new String[]{"skuId", "defaultImage", "title", "subTitle", "price"}, null);

        System.out.println(sourceBuilder);
        return sourceBuilder;
    }


}
