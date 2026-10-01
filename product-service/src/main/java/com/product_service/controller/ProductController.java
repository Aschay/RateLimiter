package com.product_service.controller;
import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.product_service.model.Product;
import com.product_service.repository.ProductRepository;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductRepository repository;

    public ProductController(ProductRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Product> getProducts() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable Long id) {
        return repository.findById(id)
                .orElseThrow();
    }
}