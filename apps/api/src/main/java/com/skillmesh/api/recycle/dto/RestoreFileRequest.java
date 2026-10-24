package com.skillmesh.api.recycle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 恢复一个被删掉的文件。 */
public record RestoreFileRequest(
        /** 制品内的相对路径（正斜杠分隔）。 */
        @NotBlank @Size(max = 1024) String path) {}
