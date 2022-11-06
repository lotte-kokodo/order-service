package shop.kokodo.orderpaymentservice.controller;

import io.restassured.RestAssured;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import shop.kokodo.orderpaymentservice.DocumentConfiguration;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.restassured3.RestAssuredRestDocumentation.document;

/**
 * packageName    : order-payment-service
 * fileName       : OrderControllerTest
 * author         : tngh1
 * date           : 2022-11-05
 * description    :
 * ===========================================================
 * DATE              AUTHOR             NOTE
 * -----------------------------------------------------------
 * 2022-11-05        tngh1              최초 생성
 */
class OrderControllerTest extends DocumentConfiguration {
    @PersistenceContext
    private EntityManager em;

    @AfterEach
    public void tearDown() {
        em.unwrap(Session.class)
                .doWork(this::cleanUpTable);
    }

    private void cleanUpTable(Connection conn) throws SQLException {
        Statement statement = conn.createStatement();
        statement.executeUpdate("SET REFERENTIAL_INTEGRITY FALSE");

        statement.executeUpdate("TRUNCATE TABLE \"ORDERS\"");
        statement.executeUpdate("TRUNCATE TABLE ORDER_PRODUCT");

        statement.executeUpdate("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @Test
    @DisplayName("주문 내역 조회")
    public void orderList() throws Exception{
        //given
        Long memberId = 1L;
        //when
        final ExtractableResponse<Response> response = RestAssured
                .given(spec).log().all()
                .filter(document("get-order-list"))
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .when().get("/orders/{memberId}")
                .then().log().all().extract();
        //then
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

//        List<OrderInformationDto> orderInformationDto = orderService.getOrderList(memberId);
//        return Response.success(orderInformationDto);
    }

    @Test
    @DisplayName("주문 상세 내역 조회")
    public void orderDetailList() throws Exception {
        //given
        Long memberId = 1L;
        Long orderId = 1L;
        //when
        final ExtractableResponse<Response> response = RestAssured
                .given(spec).log().all()
                .filter(document("get-order-detail-list"))
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .when().get("/orders/{memberId}/{orderId}")
                .then().log().all().extract();
        //then
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    }


//    @DisplayName("정산 예정날짜 조회")
//    @Test
//    public void calculateExpectDay() throws Exception{
//        //given
//        //when
//        final ExtractableResponse<Response> response = RestAssured.
//                given(spec).log().all()
//                .filter(document("calculate-expectDay"))
//                .contentType(MediaType.APPLICATION_JSON_VALUE)
//                .when().get("/calculate/expectDay")
//                .then().log().all().extract();
//        //then
//        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
//    }
}