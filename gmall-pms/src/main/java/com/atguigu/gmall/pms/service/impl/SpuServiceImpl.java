package com.atguigu.gmall.pms.service.impl;

import com.atguigu.gmall.pms.entity.*;
import com.atguigu.gmall.pms.feign.GmallSmsClient;
import com.atguigu.gmall.pms.mapper.SkuMapper;
import com.atguigu.gmall.pms.mapper.SpuDescMapper;
import com.atguigu.gmall.pms.service.*;
import com.atguigu.gmall.pms.vo.SkuVo;
import com.atguigu.gmall.pms.vo.SpuAttrValueVo;
import com.atguigu.gmall.pms.vo.SpuVo;
import com.atguigu.gmall.sms.vo.SkuSaleVo;
import io.seata.spring.annotation.GlobalTransactional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gmall.common.bean.PageResultVo;
import com.atguigu.gmall.common.bean.PageParamVo;

import com.atguigu.gmall.pms.mapper.SpuMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RestController;


@Service("spuService")
public class SpuServiceImpl extends ServiceImpl<SpuMapper, SpuEntity> implements SpuService {

    @Autowired
    private SpuDescService descService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SpuAttrValueService baseAttrService;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private SkuImagesService imagesService;

    @Autowired
    private SkuAttrValueService saleAttrService;

    @Autowired
    private GmallSmsClient smsClient;

    @Override
    public PageResultVo queryPage(PageParamVo paramVo) {
        IPage<SpuEntity> page = this.page(
                paramVo.getPage(),
                new QueryWrapper<SpuEntity>()
        );

        return new PageResultVo(page);
    }

    @Override
    public PageResultVo querySpuByCidAndPage(PageParamVo pageParamVo, Long categoryId) {
        QueryWrapper<SpuEntity> wrapper = new QueryWrapper<>();
        // category_id=225 and (id=7 OR name like '%7%')
        // ??????id??????
        if (categoryId != 0) {
            wrapper.eq("category_id", categoryId);
        }
        //???????????????
        String key = pageParamVo.getKey();
        if (StringUtils.isNotBlank(key)) {
            wrapper.and(t -> t.eq("id", key).or().like("name", key));
        }
        IPage<SpuEntity> page = this.page(
                pageParamVo.getPage(),
                wrapper
        );
        return new PageResultVo(page);
    }

    @GlobalTransactional
    @Override
    public void bigSave(SpuVo spu) {
        // 1.?????????spu????????????
        // 1.1 ??????pms_spu
        Long spuId = saveSpu(spu);

        // 1.2 ??????pms_desc
        //saveSpuDesc(spu, spuId);
        this.descService.saveSpuDesc(spu,spuId);

        // 1.3 ??????pms_spu_attr_value
        saveBaseAttr(spu, spuId);

        // 2.?????????sku????????????
        saveSkuInfo(spu, spuId);

        this.rabbitTemplate.convertAndSend("PMS_ITEM_EXCHANGE", "item.insert", spuId);

    }

    private void saveSkuInfo(SpuVo spu, Long spuId) {
        List<SkuVo> skus = spu.getSkus();
        if (CollectionUtils.isEmpty(skus)){
            return;
        }
        skus.forEach(sku -> {
            // 2.1. ??????pms_sku
            sku.setSpuId(spuId);
            sku.setCategoryId(spu.getCategoryId());
            sku.setBrandId(spu.getBrandId());
            // ??????????????????
            List<String> images = sku.getImages();
            if (!CollectionUtils.isEmpty(images)){
                sku.setDefaultImage(StringUtils.isNotBlank(sku.getDefaultImage()) ? sku.getDefaultImage() : images.get(0));
            }
            this.skuMapper.insert(sku);
            Long skuId = sku.getId();

            // 2.2. ??????pms_sku_images
            if (!CollectionUtils.isEmpty(images)){
                this.imagesService.saveBatch(images.stream().map(image -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setUrl(image);
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setDefaultStatus(StringUtils.equals(sku.getDefaultImage(), image) ? 1 : 0);
                    return skuImagesEntity;
                }).collect(Collectors.toList()));
            }

            // 2.3 ??????pms_spu_attr_value
            List<SkuAttrValueEntity> saleAttrs = sku.getSaleAttrs();
            if (!CollectionUtils.isEmpty(saleAttrs)){
                saleAttrs.forEach(skuAttrValueEntity -> skuAttrValueEntity.setSkuId(skuId));
                this.saleAttrService.saveBatch(saleAttrs);
            }


            // 3.????????????????????????
            SkuSaleVo skuSaleVo = new SkuSaleVo();
            BeanUtils.copyProperties(sku, skuSaleVo);
            skuSaleVo.setSkuId(skuId);
            this.smsClient.saveSales(skuSaleVo);
        });
    }

    private void saveBaseAttr(SpuVo spu, Long spuId) {
        List<SpuAttrValueVo> baseAttrs = spu.getBaseAttrs();
        if (!CollectionUtils.isEmpty(baseAttrs)) {
            this.baseAttrService.saveBatch(baseAttrs.stream().map(spuAttrValueVo -> {
                SpuAttrValueEntity spuAttrValueEntity = new SpuAttrValueEntity();
                BeanUtils.copyProperties(spuAttrValueVo, spuAttrValueEntity);
                spuAttrValueEntity.setSpuId(spuId);
                return spuAttrValueEntity;
            }).collect(Collectors.toList()));
        }
    }

    private Long saveSpu(SpuVo spu) {
        spu.setCreateTime(new Date());
        spu.setUpdateTime(spu.getCreateTime());
        this.save(spu);
        return spu.getId();
    }
}