package com.projectsknowledge.business.project.entity;

import com.projectsknowledge.business.project.enums.RepositoryType;
import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;

/** Repository identity and path obtained during runtime discovery. */
@Getter
@Setter
public class Repository {

    private String id;
    private String name;
    private Path path;
    private RepositoryType type;
}
