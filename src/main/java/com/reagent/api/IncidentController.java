package com.reagent.api;

import com.reagent.incident.IncidentAccepted;
import com.reagent.incident.IncidentIntakeService;
import com.reagent.incident.IncidentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentIntakeService service;

    public IncidentController(IncidentIntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentAccepted> accept(@Valid @RequestBody IncidentRequest request) {
        IncidentAccepted accepted = service.accept(request);
        HttpStatus status = accepted.deduplicated() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(accepted);
    }
}
