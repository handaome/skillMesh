package com.skillmesh.api.review.dto;

/**
 * 扫描结论摘要。
 *
 * status 留 String：除了扫描结论词表里的 passed / warning / failed / error，
 * 还有个 "unknown"——那是「这个版本没扫过」的占位，不是词表里的值。
 */
public record ReviewScanSummary(String status, String summary) {}
