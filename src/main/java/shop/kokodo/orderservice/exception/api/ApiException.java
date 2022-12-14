package shop.kokodo.orderservice.exception.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class ApiException {
    private final String message;
    private final Object result;
}
