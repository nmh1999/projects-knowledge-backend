package com.projectsknowledge.model;

import java.nio.file.Path;

/** Repository identity and path obtained during runtime discovery. */
public class Repository {
    private String id;
    private String name;
    private Path path;
    private RepositoryType type;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Path getPath() { return path; }
    public void setPath(Path path) { this.path = path; }
    public RepositoryType getType() { return type; }
    public void setType(RepositoryType type) { this.type = type; }
}
