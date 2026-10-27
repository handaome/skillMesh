package com.skillmesh.api.skill.dto;

/**
 * 一个标签及其数量。
 *
 * 数量是给标签筛选器显示的：让用户点之前就知道大概会筛出多少条。
 */
public record TagCountResponse(String tag, long count) {
}
