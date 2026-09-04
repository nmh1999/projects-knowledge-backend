package com.projectsknowledge.business.project.entity;

import com.projectsknowledge.business.project.enums.RepositoryType;
import java.nio.file.Path;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Repository identity and path obtained during runtime discovery. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Repository {

    private String id;
    private String name;
    private Path path;
    private RepositoryType type;
}
