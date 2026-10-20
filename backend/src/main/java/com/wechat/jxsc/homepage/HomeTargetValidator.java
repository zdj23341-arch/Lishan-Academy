package com.wechat.jxsc.homepage;

import com.wechat.jxsc.article.ArticleService;
import com.wechat.jxsc.catalog.*;
import com.wechat.jxsc.common.content.ContentSupport;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HomeTargetValidator {
    private final ProductService products;
    private final ArticleService articles;
    private final CategoryService categories;

    public HomeTargetValidator(
            ProductService products, ArticleService articles, CategoryService categories) {
        this.products = products;
        this.articles = articles;
        this.categories = categories;
    }

    public void validate(HomeDtos.Target target, boolean publishing) {
        if (target == null || target.type() == null || target.filters() == null)
            throw ContentSupport.invalid("Target is required");
        if (target.type() == HomeDtos.TargetType.PRODUCT
                || target.type() == HomeDtos.TargetType.ARTICLE
                || target.type() == HomeDtos.TargetType.COURSE_DETAIL) {
            long id = ContentSupport.id(target.id());
            if (!target.filters().isEmpty())
                throw ContentSupport.invalid("Detail target cannot have filters");
            if (publishing
                    && (target.type() == HomeDtos.TargetType.PRODUCT
                            ? products.publishedCard(id) == null
                            : articles.publishedSummary(id) == null))
                throw ContentSupport.invalid("Target must be published");
        } else {
            if (target.id() != null) throw ContentSupport.invalid("List target cannot have an ID");
            Set<String> allowed =
                    switch (target.type()) {
                        case PRODUCT_LIST -> Set.of("type", "categoryId", "keyword", "sort");
                        case PAPER_LIST ->
                                Set.of(
                                        "schoolStage",
                                        "stage",
                                        "grade",
                                        "subject",
                                        "year",
                                        "paperType",
                                        "type",
                                        "keyword");
                        default -> Set.of();
                    };
            if (!allowed.containsAll(target.filters().keySet()))
                throw ContentSupport.invalid("Unsupported target filter");
            for (var e : target.filters().entrySet())
                if (e.getValue() == null || e.getValue().length() > 200)
                    throw ContentSupport.invalid("Invalid target filter");
            try {
                if (target.filters().containsKey("type"))
                    Product.Type.valueOf(target.filters().get("type"));
                if (target.filters().containsKey("sort"))
                    CatalogDtos.Sort.valueOf(target.filters().get("sort"));
                if (target.filters().containsKey("year")) {
                    int y = Integer.parseInt(target.filters().get("year"));
                    if (y < 1900 || y > 2200) throw ContentSupport.invalid("Invalid year");
                }
                if (target.filters().containsKey("categoryId")) {
                    var c = categories.get(ContentSupport.id(target.filters().get("categoryId")));
                    if (!c.enabled()
                            || target.filters().containsKey("type")
                                    && !c.type().name().equals(target.filters().get("type")))
                        throw ContentSupport.invalid("Invalid category target");
                }
            } catch (IllegalArgumentException e) {
                throw ContentSupport.invalid("Invalid target filter");
            }
        }
    }
}
