package com.atguigu.gmall.pms.vo;

import com.atguigu.gmall.pms.entity.SpuEntity;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SpuVo extends SpuEntity {

    private List<String> spuImages;
    private List<SpuAttrValueVo> baseAttrs;
    private List<SkuVo> skus;



}
