package com.threadly.payments.charge;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/charges")
public class ChargeController {

    private final ChargeService service;

    public ChargeController(ChargeService service) {
        this.service = service;
    }

    @PostMapping
    public ChargeResponse create(@Valid @RequestBody ChargeRequest request) {
        Charge c = service.create(request);
        return ChargeResponse.fromEntity(c);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChargeResponse> get(@PathVariable String id) {
        return service.findById(id)
                .map(ChargeResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
