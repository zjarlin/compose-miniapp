package com.addzero.miniapp;

import java.util.List;

record OrderRecord(String id, String storeId, String title, String amount, String status, String createdAt, List<OrderLine> items) {
}

record OrderLine(String id, String name, int quantity, int unitPriceCent) {
}
