package com.projectsknowledge.business.project.mapper;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.schema.response.DtoProjectOverview;
import com.projectsknowledge.business.project.schema.response.DtoRepository;
import java.nio.file.Files;
import java.util.List;

/** Maps the cheap project listing without triggering repository analysis. */
public final class ProjectMapper {

    private ProjectMapper() {}

    public static DtoProject toSummaryDto(Project project) {
        List<DtoRepository> repositories = project
            .getRepositories()
            .stream()
            .map(repository ->
                DtoRepository.builder()
                    .id(repository.getId())
                    .name(repository.getName())
                    .type(repository.getType().name())
                    .available(Files.isDirectory(repository.getPath()))
                    .languages(List.of())
                    .frameworks(List.of())
                    .buildTools(List.of())
                    .build()
            )
            .toList();

        return DtoProject.builder()
            .id(project.getId())
            .name(project.getName())
            .repositories(repositories)
            .overview(
                new DtoProjectOverview(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
            )
            .build();
    }
}
