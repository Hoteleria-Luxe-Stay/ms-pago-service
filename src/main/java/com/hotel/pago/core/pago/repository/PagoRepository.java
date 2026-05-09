package com.hotel.pago.core.pago.repository;

import com.hotel.pago.core.pago.model.Pago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PagoRepository extends JpaRepository<Pago, Long> {

    Optional<Pago> findByGatewayPaymentId(String gatewayPaymentId);

    List<Pago> findByReservaId(Long reservaId);
}
