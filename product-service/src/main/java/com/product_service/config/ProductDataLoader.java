package com.product_service.config;
import com.github.javafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.product_service.model.Product;
import com.product_service.repository.ProductRepository;

@Configuration
public class ProductDataLoader {

    @Bean
    CommandLineRunner loadProducts(ProductRepository repository) {
        return args -> {

            Faker faker = new Faker();

            for (int i = 0; i < 100; i++) {

                String name = faker.commerce().productName();
                String category = faker.commerce().department();
                double price = faker.number().randomDouble(2, 10, 500);

                Product product = new Product(
                        name,
                        category,
                        price
                );

                repository.save(product);
            }
        };
    }
}