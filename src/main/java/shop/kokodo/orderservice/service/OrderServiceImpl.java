package shop.kokodo.orderservice.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import java.util.stream.Collectors;

import com.querydsl.core.QueryResults;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.kokodo.orderservice.dto.request.CartOrderDto;
import shop.kokodo.orderservice.dto.request.SingleProductOrderDto;
import shop.kokodo.orderservice.dto.response.*;
import shop.kokodo.orderservice.entity.Cart;
import shop.kokodo.orderservice.entity.Order;
import shop.kokodo.orderservice.entity.OrderProduct;
import shop.kokodo.orderservice.entity.QOrderProduct;
import shop.kokodo.orderservice.entity.enums.status.CartStatus;
import shop.kokodo.orderservice.feign.client.MemberServiceClient;
import shop.kokodo.orderservice.feign.client.ProductServiceClient;
import shop.kokodo.orderservice.feign.client.PromotionServiceClient;
import shop.kokodo.orderservice.feign.response.OrderMemberDto;
import shop.kokodo.orderservice.feign.response.OrderProductDto;
import shop.kokodo.orderservice.feign.response.ProductThumbnailDto;
import shop.kokodo.orderservice.feign.response.RateCouponDto;
import shop.kokodo.orderservice.feign.response.RateDiscountPolicyDto;
import shop.kokodo.orderservice.kafka.KafkaProducer;
import shop.kokodo.orderservice.kafka.dto.CouponNameDto;
import shop.kokodo.orderservice.repository.interfaces.CartRepository;
import shop.kokodo.orderservice.repository.interfaces.OrderProductRepository;
import shop.kokodo.orderservice.repository.interfaces.OrderRepository;
import shop.kokodo.orderservice.service.interfaces.OrderService;
import shop.kokodo.orderservice.service.utils.ProductPriceCalculator;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {



    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final OrderProductRepository orderProductRepository;

    private final ProductPriceCalculator productPriceCalculator;

    // Feign Service
    private final ProductServiceClient productServiceClient;
    private final MemberServiceClient memberServiceClient;
    private final PromotionServiceClient promotionServiceClient;

    //CircuitBreaker
    private final CircuitBreakerFactory circuitBreakerFactory;

    //Kafka
    private final KafkaProducer kafkaProducer;

    //queryDSL
    private final JPAQueryFactory jpaQueryFactory;
    private static QOrderProduct orderProduct = QOrderProduct.orderProduct;

    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            CartRepository cartRepository,
            ProductServiceClient productServiceClient,
            MemberServiceClient memberServiceClient,
            OrderProductRepository orderProductRepository,
            PromotionServiceClient promotionServiceClient,
            ProductPriceCalculator productPriceCalculator,
            CircuitBreakerFactory circuitBreakerFactory,
            JPAQueryFactory jpaQueryFactory,
            KafkaProducer kafkaProducer) {

        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productPriceCalculator = productPriceCalculator;
        this.orderProductRepository = orderProductRepository;
        this.productServiceClient = productServiceClient;
        this.memberServiceClient = memberServiceClient;
        this.promotionServiceClient = promotionServiceClient;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.jpaQueryFactory = jpaQueryFactory;
        this.kafkaProducer = kafkaProducer;
    }

    @Transactional(readOnly = false)
    public Order orderSingleProduct(SingleProductOrderDto dto) {
        Long productId = dto.getProductId();
        Long memberId = dto.getMemberId();
        Integer qty = dto.getQty();

        // ?????? ??????
        OrderProductDto orderProductDto = productServiceClient.getSingleOrderProduct(productId);

        // ?????? ?????? ??????
        List<OrderProduct> orderProducts = List.of(OrderProduct.createOrderProduct(dto, orderProductDto));

        // ????????? ??????, ??????
        OrderMemberDto orderMemberDto = memberServiceClient.getOrderMember(dto.getMemberId());

        // [promotion-service feign]
        // ??????????????????, ??????????????????, ????????????, ???????????? ??????
        Long sellerId = dto.getSellerId();
        Map<Long, RateDiscountPolicyDto> rateDiscountPolicyMap = promotionServiceClient.getRateDiscountPolicy(List.of(productId));
        Map<Long, Boolean> fixDiscountPolicySellerMap = promotionServiceClient.getFixDiscountPolicyStatusForFeign(List.of(productId), List.of(sellerId));

        // TODO: ID ??? NULL ??? ?????? ??????
        Long rateCouponId = dto.getRateCouponId();
        Long fixCouponId = dto.getFixCouponId();
        Map<Long, RateCouponDto> rateCouponMap = (rateCouponId != null) ?  promotionServiceClient.findRateCouponByCouponIdList(List.of(rateCouponId)) : new LinkedHashMap<>();
        List<Long> fixCouponSellerIds = (fixCouponId != null) ? promotionServiceClient.findFixCouponByCouponIdList(List.of(fixCouponId)) : new ArrayList<>();

        // ????????????
        // ??????????????????, ?????????????????? ??????
        Map<Long, Long> productSellerMap = new HashMap<>(){{ put(productId, sellerId); }};
        Integer totalPrice = productPriceCalculator.calcTotalPrice(orderProducts, productSellerMap, rateDiscountPolicyMap, fixDiscountPolicySellerMap, rateCouponMap, fixCouponSellerIds);

        // ?????? ??????
        Order order = Order.createOrder(memberId, orderMemberDto.getName(), orderMemberDto.getAddress(), totalPrice, orderProducts);
        orderRepository.save(order);

        kafkaProducer.send("product-decrease-stock", new LinkedHashMap<>() {{
            put(productId, qty);
        }});

        // ?????? ?????? ??????
        CouponNameDto couponNameDto = getValidCouponNameDto(memberId, rateCouponId, fixCouponId, rateCouponMap);
        if (couponNameDto != null) {
            kafkaProducer.send("promotion-coupon-status", couponNameDto);
        }

        return order;
    }

    @Transactional(readOnly = false)
    public Order orderCartProducts(CartOrderDto dto) {

        List<Long> rateCouponIds = dto.getRateCouponIds();
        List<Long> fixCouponIds = dto.getFixCouponIds();

        // '??????????????????' ??????
        List<Cart> carts = cartRepository.findByIdIn(dto.getCartIds());

        List<Long> cartProductIds = carts.stream().map(Cart::getProductId).collect(Collectors.toList());
        Map<Long, OrderProductDto> orderProductDtoMap = productServiceClient.getCartOrderProduct(cartProductIds);

        // ?????? ?????? ??????
        List<OrderProduct> orderProducts = carts.stream()
            .map((cart) -> OrderProduct.createOrderProduct(cart, orderProductDtoMap.get(cart.getProductId())))
            .collect(Collectors.toList());

        Map<Long, Long> productSellerMap = orderProductDtoMap.values().stream()
            .collect(Collectors.toMap(OrderProductDto::getId, OrderProductDto::getSellerId, (product1, product2) -> product1));
        List<Long> productIds = new ArrayList<>();
        List<Long> sellerIds = new ArrayList<>();
        productSellerMap.keySet().forEach((productId) -> {
            productIds.add(productId);
            sellerIds.add(productSellerMap.get(productId));
        });

        // [promotion-service feign]
        // ??????????????????, ??????????????????, ????????????, ???????????? ??????
        Map<Long, RateDiscountPolicyDto> rateDiscountPolicyMap = promotionServiceClient.getRateDiscountPolicy(productIds);
        Map<Long, Boolean> fixDiscountPolicySellerMap = promotionServiceClient.getFixDiscountPolicyStatusForFeign(productIds, sellerIds);
        Map<Long, RateCouponDto> rateCouponMap = promotionServiceClient.findRateCouponByCouponIdList(rateCouponIds);
        List<Long> fixCouponSellerIds = promotionServiceClient.findFixCouponByCouponIdList(fixCouponIds);

        // ?????? ??? ?????? ??????
        Integer totalPrice = productPriceCalculator.calcTotalPrice(orderProducts, productSellerMap, rateDiscountPolicyMap, fixDiscountPolicySellerMap, rateCouponMap, fixCouponSellerIds);

        // ????????? ??????, ??????
        Long memberId = dto.getMemberId();
        OrderMemberDto orderMemberDto = memberServiceClient.getOrderMember(memberId);

        Order order = Order.createOrder(memberId, orderMemberDto.getName(), orderMemberDto.getAddress(), totalPrice, orderProducts);
        orderRepository.save(order);

        // ???????????? ?????? ????????????
        carts.forEach((cart -> cart.changeStatus(CartStatus.ORDER_PROCESS)));
        cartRepository.saveAll(carts);

        // ?????? ?????? ??????
        Map<Long, Integer> productIdQtyMap = carts.stream()
                .collect(Collectors.toMap(Cart::getProductId, Cart::getQty, Integer::sum));
        kafkaProducer.send("product-decrease-stock", productIdQtyMap);

        // ?????? ?????? ??????
        CouponNameDto couponNameDto = getValidCouponNameDto(memberId, rateCouponIds, fixCouponIds, rateCouponMap);
        if (couponNameDto != null) {
            kafkaProducer.send("promotion-coupon-status", couponNameDto);
        }
        return order;
    }

    // ?????? ????????? NULL ??????
    // ?????? ????????? ????????? NULL ??????
    public CouponNameDto getValidCouponNameDto(Long memberId,
        Object rateCouponId, Object fixCouponId,
        Map<Long, RateCouponDto> rateCouponMap) {

        // ?????? ??? ????????? ???????????? ????????????,
        if (rateCouponId == null && fixCouponId == null) {
            return null;
        }

        List<String> rateCouponNames = rateCouponMap.values().stream()
            .map(RateCouponDto::getName).collect(Collectors.toList());

        // ?????? ????????? ???????????????,
        if (rateCouponId instanceof List) {
            List<Long> rateCouponIds = (List<Long>) rateCouponId;
            List<Long> fixCouponIds = (List<Long>) fixCouponId;
            // ?????? ????????? ???????????? ?????? ????????????,
            if (rateCouponIds.isEmpty() && fixCouponIds.isEmpty()) {
                return null;
            }

            // ??????-?????? ?????? ??? ????????? ??????????????? ???????????????,
            return new CouponNameDto(memberId, fixCouponIds, rateCouponNames);
        }

        // ??????????????? ???????????????,
        if (rateCouponId != null) {
            return new CouponNameDto(memberId, new ArrayList<>(), rateCouponNames);
        }
        else {
            return new CouponNameDto(memberId, List.of((Long) fixCouponId), rateCouponNames);
        }

    }

    @Transactional(readOnly = false)
    @Override
    public PagingOrderInformationDto getOrderList(Long memberId, int page) {
        Page<Order> orderPage = orderRepository.findAllByMemberId(memberId, PageRequest.of(page,5));
        List<Order> orderList = orderPage.get().collect(Collectors.toList());

        List<OrderProductThumbnailDto> orderProductThumbnailDtoList = orderProductRepository.findAllByOrderIdIn(
                orderList.stream()
                        .map(Order::getId)
                        .collect(Collectors.toList()
                        )
        );

        List<Long> productIdList = orderProductThumbnailDtoList.stream()
                .map(OrderProductThumbnailDto::getProductId)
                .collect(Collectors.toList());

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        Map<Long, ProductThumbnailDto> productList = circuitBreaker.run(
                () -> productServiceClient.getProductListMap(productIdList),
                throwable -> new HashMap<Long, ProductThumbnailDto>()
        );

        List<OrderInformationDto> response = new ArrayList<>();
        for (int i=0;i< orderProductThumbnailDtoList.size();i++) {
            Long productId = productIdList.get(i);
            ProductThumbnailDto product = productList.get(productId);

            String name = "";
            if(orderProductThumbnailDtoList.get(i).getCount() == 1) name = product.getName();
            else name = product.getName() + " ??? " + orderProductThumbnailDtoList.get(i).getCount() + "???";

            if(product != null) {
                if(orderProductThumbnailDtoList.get(i).getCount() == 1) name = product.getName();
                else name = product.getName() + " ??? " + (orderProductThumbnailDtoList.get(i).getCount() - 1) + "???";

                response.add(OrderInformationDto.builder()
                        .orderId(orderList.get(i).getId())
                        .name(name)
                        .orderStatus(orderList.get(i).getOrderStatus())
                        .price(orderList.get(i).getTotalPrice())
                        .thumbnail(product.getThumbnail())
                        .orderDate(orderList.get(i).getOrderDate())
                        .build()
                );
            }
        }
        log.info("response : " + response);

        return PagingOrderInformationDto.builder()
                .orderInformationDtoList(response)
                .totalCount(orderPage.getTotalElements())
                .build();
    }

    @Transactional(readOnly = false)
    @Override
    public PagingOrderInformationDto getOrderListDsl(Long memberId, int page) {
        Page<Order> orderPage = orderRepository.findAllByMemberId(memberId, PageRequest.of(page,5));
        List<Order> orderList = orderPage.get().collect(Collectors.toList());

        List<Long> orderIdList = orderList.stream()
                .map(Order::getId)
                .collect(Collectors.toList());

        List<OrderProductDslDto> orderProductDtoListDsl = findAllByOrderIdInDsl(orderIdList);

        List<Long> productIdList = orderProductDtoListDsl.stream()
                .map(OrderProductDslDto::getProductId)
                .collect(Collectors.toList());

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        Map<Long, ProductThumbnailDto> productList = circuitBreaker.run(
                () -> productServiceClient.getProductListMap(productIdList),
                throwable -> new HashMap<Long, ProductThumbnailDto>()
        );

        List<OrderInformationDto> response = new ArrayList<>();
        for (int i=0;i<orderProductDtoListDsl.size();i++) {
            Long productId = productIdList.get(i);
            ProductThumbnailDto product = productList.get(productId);

            String name = "";
            if(orderProductDtoListDsl.get(i).getCount() == 1) name = product.getName();
            else name = product.getName() + " ??? " + orderProductDtoListDsl.get(i).getCount() + "???";

            if(product != null) {
                response.add(OrderInformationDto.builder()
                        .orderId(orderList.get(i).getId())
                        .name(product.getName() + " ??? " + orderProductDtoListDsl.get(i).getCount() + "???")
                        .orderStatus(orderList.get(i).getOrderStatus())
                        .price(orderList.get(i).getTotalPrice())
                        .thumbnail(product.getThumbnail())
                        .orderDate(orderList.get(i).getOrderDate())
                        .build()
                );
            }
        }
        log.info("response : " + response);

        return PagingOrderInformationDto.builder()
                .orderInformationDtoList(response)
                .totalCount(orderPage.getTotalElements())
                .build();
    }


    @Transactional(readOnly = false)
    @Override
    public List<OrderDetailInformationDto> getOrderDetailList(Long memberId, Long orderId) {
        log.info("memberID : " + memberId + ", orderId : " + orderId);
        List<OrderProduct> orderProductList = orderProductRepository.findAllByIdAndMemberId(memberId, orderId);
        log.info("orderProductList : " + orderProductList.toString());

        List<Long> productIdList = orderProductList.stream()
                .map(OrderProduct::getProductId)
                .collect(Collectors.toList());
        log.info("productIdList : " + productIdList.toString());
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        Map<Long, ProductThumbnailDto> productList = circuitBreaker.run(
                () -> productServiceClient.getProductListMap(productIdList),
                throwable -> new HashMap<Long, ProductThumbnailDto>()
        );

        List<OrderDetailInformationDto> orderDetailInformationDtoList = new ArrayList<>();

        for (int i = 0; i < orderProductList.size(); i++) {
            OrderDetailInformationDto orderDetailInformationDto = OrderDetailInformationDto.builder()
                    .id(orderProductList.get(i).getId())
                    .name(productList.get(productIdList.get(i)).getName())
                    .price(orderProductList.get(i).getUnitPrice())
                    .qty(orderProductList.get(i).getQty())
                    .thumbnail(productList.get(productIdList.get(i)).getThumbnail())
                    .orderStatus(orderProductList.get(i).getOrder().getOrderStatus())
                    .build();
            orderDetailInformationDtoList.add(orderDetailInformationDto);
        }

        return orderDetailInformationDtoList;
    }

    @Transactional(readOnly = true)
    @Override
    public Map<Long, List<Integer>> getProductAllPrice(List<Long> productIdList) {

        Calendar cal = Calendar.getInstance(Locale.KOREA);
        LocalDateTime startDate = getDate("start");
        LocalDateTime endDate = getDate("end");

        List<OrderProduct> orderPriceDtoList = orderProductRepository.findByProductIdListAndSellerId(productIdList, startDate, endDate);
        Map<Long, List<Integer>> result = new HashMap<>();
        for(OrderProduct orderProduct : orderPriceDtoList) {
            List<Integer> list = new ArrayList<>();
            list.add(orderProduct.getUnitPrice());
            list.add(orderProduct.getQty());
            result.put(orderProduct.getProductId(), list);
        }

        return result;
    }

    @Transactional(readOnly = true)
    @Override
    public Boolean findByMemberIdAndProductId(Long memberId, Long productId) {
        List<OrderProduct> orderDto = orderProductRepository.findByMemberIdAndProductId(memberId, productId);
        return !orderDto.isEmpty();
    }

    LocalDateTime getDate(String flag) {
        Calendar cal = Calendar.getInstance(Locale.KOREA);
        //?????? ?????? ??????
        if(flag.equals("start")) {
            cal.add(Calendar.DATE, 2 - cal.get(Calendar.DAY_OF_WEEK));
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
        }
        //?????? ?????? ??????
        else if(flag.equals("end")){
            cal.add(Calendar.DATE, 8 - cal.get(Calendar.DAY_OF_WEEK));
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
        }
        TimeZone tz = cal.getTimeZone();
        ZoneId zoneId = tz.toZoneId();

        return LocalDateTime.ofInstant(cal.toInstant(), zoneId);
    }

    private List<OrderProductDslDto> findAllByOrderIdInDsl(List<Long> orderIdList) {
        orderProduct = QOrderProduct.orderProduct;
        QueryResults<Tuple> results = jpaQueryFactory.select(orderProduct.productId, orderProduct.count(), orderProduct.order.id)
                .from(orderProduct)
                .where(orderProduct.order.id.in(orderIdList))
                .groupBy(orderProduct.order.id)
                .fetchResults();

        List<OrderProductDslDto> result = new ArrayList<>();
        results.getResults().stream().forEach(tuple -> result.add(
                OrderProductDslDto.builder()
                        .productId(tuple.get(0, Long.class))
                        .count(tuple.get(1, Long.class))
                        .orderId(tuple.get(2, Long.class))
                        .build()
                )
        );
        return result;
    }
}
