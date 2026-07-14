package cn.edu.xxq.faceenrollment

import cn.edu.xxq.faceenrollment.data.CatalogProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderFormLogicTest {
    private val products = listOf(
        CatalogProduct("water", "矿泉水", true),
        CatalogProduct("snack", "饼干", true),
        CatalogProduct("hidden", "已下架", false),
    )

    @Test
    fun validation_requiresIdentityRoomAndSelectedProduct() {
        assertEquals(
            "请输入已录入人脸的姓名",
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = " ",
                roomNumber = "A302",
                catalog = products,
                productQuantities = mapOf("water" to 1),
                lastEnrolledPersonId = "person-123",
                lastEnrolledName = "张三",
            ),
        )
        assertEquals(
            "请输入房间号",
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = "张三",
                roomNumber = " ",
                catalog = products,
                productQuantities = mapOf("water" to 1),
                lastEnrolledPersonId = "person-123",
                lastEnrolledName = "张三",
            ),
        )
        assertEquals(
            "请至少选择一件商品",
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = "张三",
                roomNumber = "A302",
                catalog = products,
                productQuantities = emptyMap(),
                lastEnrolledPersonId = "person-123",
                lastEnrolledName = "张三",
            ),
        )
    }

    @Test
    fun requestItems_onlyKeepsEnabledProductsWithValidQuantities() {
        val items = OrderFormLogic.requestItems(
            catalog = products,
            productQuantities = mapOf(
                "water" to 2,
                "snack" to 100,
                "hidden" to 1,
                "unknown" to 4,
            ),
        )

        assertEquals(1, items.size)
        assertEquals("water", items.single().productId)
        assertEquals(2, items.single().quantity)
    }

    @Test
    fun validation_acceptsCompleteOrder() {
        assertNull(
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = " 张三 ",
                roomNumber = " A302 ",
                catalog = products,
                productQuantities = mapOf("water" to 2, "snack" to 1),
                lastEnrolledPersonId = "person-123",
                lastEnrolledName = "张三",
            ),
        )
    }

    @Test
    fun validation_requiresCurrentNameToHaveAStableEnrolledIdentity() {
        assertEquals(
            "请先使用“李四”在人脸采集页面完成人脸录入",
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = "李四",
                roomNumber = "A302",
                catalog = products,
                productQuantities = mapOf("water" to 1),
                lastEnrolledPersonId = "person-123",
                lastEnrolledName = "张三",
            ),
        )
        assertEquals(
            "请先使用“张三”在人脸采集页面完成人脸录入",
            OrderFormLogic.validationError(
                serviceUrl = "http://10.39.132.165:9095",
                personName = "张三",
                roomNumber = "A302",
                catalog = products,
                productQuantities = mapOf("water" to 1),
                lastEnrolledPersonId = null,
                lastEnrolledName = "张三",
            ),
        )
    }
}
