package com.hotel.pago.helpers.exceptions;

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String entity, Object id) {
        super(entity + " no encontrado: " + id);
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
