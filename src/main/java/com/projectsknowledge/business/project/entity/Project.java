package com.projectsknowledge.business.project.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Runtime project discovered from Codex, not a configured list of known projects. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    private String id;
    private String name;
    @Builder.Default
    private List<Repository> repositories = new ArrayList<>();
}
