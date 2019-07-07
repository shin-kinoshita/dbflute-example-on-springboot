package org.docksidestage.app.web.lido.product;

// the 'lido' package is example for JSON API in simple project
// client application is riot.js in lidoisle directory

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.dbflute.cbean.result.PagingResultBean;
import org.docksidestage.app.web.base.paging.PagingAssist;
import org.docksidestage.app.web.base.paging.SearchPagingResult;
import org.docksidestage.dbflute.exbhv.ProductBhv;
import org.docksidestage.dbflute.exentity.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author y.shimizu
 */
@RestController
@RequestMapping("/lido/product")
public class ProductController {

    @Autowired
    private ProductBhv productBhv;
    @Autowired
    private PagingAssist pagingAssist;

    @GetMapping("/list")
    public SearchPagingResult<ProductRowResult> list(Optional<Integer> pageNumber, ProductSearchBody body) {

        // TODO validateApi(body, messages -> {});

        PagingResultBean<Product> page = selectProductPage(pageNumber.orElse(1), body);
        List<ProductRowResult> items = page.stream().map(product -> {
            return mappingToBean(product);
        }).collect(Collectors.toList());

        SearchPagingResult<ProductRowResult> result = pagingAssist.createPagingResult(page, items);
        return result;
    }

    // ===================================================================================
    //                                                                              Select
    //                                                                              ======
    private PagingResultBean<Product> selectProductPage(int pageNumber, ProductSearchBody body) {
        // TODO verifyOrClientError("The pageNumber should be positive number: " + pageNumber, pageNumber > 0);
        return productBhv.selectPage(cb -> {
            cb.setupSelect_ProductStatus();
            cb.setupSelect_ProductCategory();
            cb.specify().derivedPurchase().count(purchaseCB -> {
                purchaseCB.specify().columnPurchaseId();
            }, Product.ALIAS_purchaseCount);
            if (!StringUtils.isEmpty(body.productName)) {
                cb.query().setProductName_LikeSearch(body.productName, op -> op.likeContain());
            }
            if (!StringUtils.isEmpty(body.purchaseMemberName)) {
                cb.query().existsPurchase(purchaseCB -> {
                    purchaseCB.query().queryMember().setMemberName_LikeSearch(body.purchaseMemberName, op -> op.likeContain());
                });
            }
            if (body.productStatus != null) {
                cb.query().setProductStatusCode_Equal_AsProductStatus(body.productStatus);
            }
            cb.query().addOrderBy_ProductName_Asc();
            cb.query().addOrderBy_ProductId_Asc();
            cb.paging(Integer.MAX_VALUE, pageNumber); // #later: waiting for client side implementation by jflute
        });
    }

    // ===================================================================================
    //                                                                             Mapping
    //                                                                             =======
    private ProductRowResult mappingToBean(Product product) {
        ProductRowResult bean = new ProductRowResult();
        bean.productId = product.getProductId();
        bean.productName = product.getProductName();
        product.getProductStatus().alwaysPresent(status -> {
            bean.productStatusName = status.getProductStatusName();
        });
        bean.regularPrice = product.getRegularPrice();
        return bean;
    }
}
