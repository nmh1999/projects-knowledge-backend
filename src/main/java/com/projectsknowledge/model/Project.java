package com.projectsknowledge.model;

import java.util.ArrayList;
import java.util.List;

/** Runtime project discovered from Codex, not a configured list of known projects. */
public class Project {
    private String id;
    private String name;
    private List<Repository> repositories = new ArrayList<>();
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Repository> getRepositories() { return repositories; }
    public void setRepositories(List<Repository> repositories) { this.repositories = repositories; }
}
