package shop.kokodo.orderpaymentservice.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.kokodo.orderpaymentservice.dto.response.dto.MemberResponse;
import shop.kokodo.orderpaymentservice.dto.response.dto.OrderInformationDto;
import shop.kokodo.orderpaymentservice.dto.response.dto.OrderResponse;
import shop.kokodo.orderpaymentservice.entity.*;
import shop.kokodo.orderpaymentservice.repository.interfaces.CartRepository;
import shop.kokodo.orderpaymentservice.repository.interfaces.OrderProductRepository;
import shop.kokodo.orderpaymentservice.repository.interfaces.OrderRepository;
import shop.kokodo.orderpaymentservice.repository.interfaces.ProductRepository;
import shop.kokodo.orderpaymentservice.service.interfaces.OrderService;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private final ModelMapper modelMapper;

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    /* feignclient 전 productRepository사용을 위한 repository */
    private final ProductRepository productRepository;
    private final OrderProductRepository orderProductRepository;

    // FeignClient
//    private final MemberServiceClient memberServiceClient;
//    private final ProductServiceClient productServiceClient;

    @Autowired
    public OrderServiceImpl(
            ModelMapper modelMapper,
            OrderRepository orderRepository,
            CartRepository cartRepository,
            ProductRepository productRepository,
            OrderProductRepository orderProductRepository) {

        this.modelMapper = modelMapper;
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.orderProductRepository = orderProductRepository;
    }

    @Transactional
    public Long orderSingleProduct(Long memberId, Long productId, Integer qty) {

        // TODO: FeignClient 통신 테스트
        // 상품 가격
//        ProductResponse productResponse = productServiceClient.getProduct(productId);
//        Integer unitPrice = productResponse.getUnitPrice();
        Integer unitPrice = 10000;

        // 주문 상품 생성
        OrderProduct orderProduct = OrderProduct.builder()
                .memberId(memberId)
                .productId(productId)
                .qty(qty)
                .unitPrice(unitPrice)
                .build();


        // TODO: FeignClient 통신 테스트
        // 사용자 이름, 주소
//        MemberResponse memberResponse = memberServiceClient.getMember(memberId);
        MemberResponse memberResponse = new MemberResponse("NaYeon Kwon",
                "서울특별시 강남구 가로수길 43");

        // 주문 생성
        Order order = Order.builder()
                .deliveryMemberName(memberResponse.getMemberName())
                .deliveryMemberAddress(memberResponse.getMemberAddress())
                .totalPrice(unitPrice * qty)
                .orderDate(LocalDateTime.now())
                .orderProducts(List.of(orderProduct))
                .build();

        orderRepository.save(order);

        return order.getId();
    }

    @Transactional
    public Long orderCartProducts(Long memberId, List<Long> cartIds) {
        // '장바구니상품' 조회
        List<Cart> carts = cartRepository.findByIdIn(cartIds);

        // 주문 상품 생성
        List<OrderProduct> orderProducts = carts.stream()
                .map(cart -> modelMapper.map(cart, OrderProduct.class))
                .collect(Collectors.toList());

        // 단일상품가격(unitPrice) 세팅
        List<Long> productIds = carts.stream()
                .map(Cart::getProductId)
                .collect(Collectors.toList());
        ;

//        List<ProductResponse> productResponses = productServiceClient.getProducts(productIds);
//        modelMapper.map(productResponses, orderProducts);

        // 주문 총 가격 계산
        Integer totalPrice = orderProducts.stream()
                .map(OrderProduct::getUnitPrice)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        // 사용자 이름, 주소
//        MemberResponse memberResponse = memberServiceClient.getMember(memberId);
        MemberResponse memberResponse = new MemberResponse("NaYeon Kwon",
                "서울특별시 강남구 가로수길 43");

        // 주문 생성
        Order order = Order.builder()
                .deliveryMemberName(memberResponse.getMemberName())
                .deliveryMemberAddress(memberResponse.getMemberAddress())
                .totalPrice(totalPrice)
                .orderDate(LocalDateTime.now())
                .orderProducts(orderProducts)
                .build();

        orderRepository.save(order);

        return order.getId();
    }

    @Override
    public List<OrderInformationDto> getOrderList(Long memberId) {
        //1. memberId로 OrderProduct들 갖고오기
        //2. OrderProduct들로 productId들 갖고오기
        List<Order> orderList = orderRepository.findByMemberId(memberId);
        log.info("orderList : " + orderList.toString());
        log.info("orderList -> orderProducts : " + orderList.get(0).getOrderProducts().toString());
        List<Long> productIdList =
                orderList.stream()
                        .map(Order::getOrderProducts)
                        .flatMap(Collection::stream)
                        .map(OrderProduct::getProductId)
                        .collect(Collectors.toList());
        log.info("orderProductIdList : " + productIdList.toString());
        //3. productId들로 Product들 갖고오기
        List<Product> productList = productRepository.findByIdIn(productIdList);
        log.info("productList : " + productList.get(0).toString());
        //4. Product들을 Response와 합쳐서 보내기
        List<OrderInformationDto> orderInformationDtoList = new ArrayList<>();
        for(int i=0;i<orderList.size();i++) {
            //주문번호
            Long orderId = orderList.get(i).getId();
            String name = "";
            if(productList.size() != 0) {
                //제목
                String orderName = productList.get(i).getDisplayName();
                if(productIdList.size() != 1) {
                    name = orderName + " 외 " + productIdList.size() + "건";
                }else {
                    name = orderName;
                }
            }
            //주문시간
            LocalDateTime orderDate = orderList.get(i).getOrderDate();
            //썸네일
            String thumbnail = productList.get(0).getThumbnail();
            //결제금액
            int price = orderList.get(i).getTotalPrice();
            //주문상태
            OrderStatus orderStatus = orderList.get(i).getOrderStatus();

            OrderInformationDto orderInformationDto = OrderInformationDto.builder()
                    .orderId(orderId)
                    .name(name)
                    .orderStatus(orderStatus)
                    .price(price)
                    .thumbnail(thumbnail)
                    .orderDate(orderDate)
                    .build();
            orderInformationDtoList.add(orderInformationDto);
        }



        return orderInformationDtoList;
    }

    @Override
    public List<OrderResponse> getOrderDetailList(Long memberId) {
        return null;
    }

}
