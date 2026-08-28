package com.projectsknowledge.business.project.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Runtime project discovered from Codex, not a configured list of known projects. */
@Getter
@Setter
public class Project {

    private String id;
    private String name;
    private List<Repository> repositories = new ArrayList<>();
}
