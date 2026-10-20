package com.skillmesh.api.review.dto;

/** 来源摘要：代码从哪儿来的。 */
public record ReviewSourceSummary(String provider, String repository, String ref, String commit) {}
