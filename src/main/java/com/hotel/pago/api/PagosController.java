package com.hotel.pago.api;

import com.hotel.pago.api.dto.CrearPagoRequest;
import com.hotel.pago.api.dto.CrearPagoResponse;
import com.hotel.pago.api.dto.PagoResponse;
import com.hotel.pago.core.pago.service.PagoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pagos")
public class PagosController {

    private final PagoService pagoService;

    public PagosController(PagoService pagoService) {
        this.pagoService = pagoService;
    }

    @PostMapping
    public ResponseEntity<CrearPagoResponse> crearPago(@Valid @RequestBody CrearPagoRequest request) {
        CrearPagoResponse response = pagoService.crearPago(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PagoResponse> obtenerPago(@PathVariable Long id) {
        PagoResponse response = pagoService.obtenerPorId(id);
        return ResponseEntity.ok(response);
    }
}
