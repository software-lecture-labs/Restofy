-- MENU-03 / TASK 3.1: optional customer-facing ingredients text of a product.
--
-- Display text only. It is NOT a structured ingredient list: it creates no removable/addable options, no price
-- difference and no allergen information (those are separate, later work). NULL means "not provided";
-- existing products keep NULL and nothing is invented for them. A blank value is never stored, and the
-- 1000-character limit is enforced here as well as in the application.

ALTER TABLE products ADD COLUMN ingredients_text varchar(1000);

ALTER TABLE products ADD CONSTRAINT ck_products_ingredients_text
    CHECK (ingredients_text IS NULL OR (btrim(ingredients_text) <> '' AND char_length(ingredients_text) <= 1000));
