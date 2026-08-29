package com.projectsknowledge.general.desktop;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes shutdown only to the packaged UI running on the local machine. */
@RestController
@RequestMapping("/api/desktop")
@RequiredArgsConstructor
public class DesktopController {

    static final String DESKTOP_HEADER = "X-Projects-Knowledge-Desktop";
    private final ProjectsKnowledgeProperties properties;
    private final DesktopApplicationService desktopApplication;

    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown(
        HttpServletRequest request,
        @RequestHeader(name = DESKTOP_HEADER, required = false) String desktopHeader
    ) {
        if (!properties.getDesktop().isEnabled()) return ResponseEntity.notFound().build();
        if (!"true".equals(desktopHeader) || !isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        desktopApplication.requestShutdown();
        return ResponseEntity.accepted().build();
    }

    private boolean isLoopback(String remoteAddress) {
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
