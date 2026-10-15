
#!/usr/bin/env sh
set -eu

# RESTOFY - INITIAL DATABASE CREATION

for db_name in \
    "$DB_ADMIN_NAME" \
    "$DB_AUTH_NAME" \
    "$DB_KITCHEN_NAME" \
    "$DB_MAIL_NAME" \
    "$DB_MENU_NAME" \
    "$DB_NOTIFICATION_NAME" \
    "$DB_ORDER_NAME" \
    "$DB_PAYMENT_NAME" \
    "$DB_QR_NAME" \
    "$DB_RATING_NAME" \
    "$DB_TIMER_NAME" \
    "$DB_WAITER_NAME"
do
    if [ -z "$db_name" ]; then
        echo "[ERROR] Database name cannot be empty."
        exit 1
    fi

    psql \
        --username "$POSTGRES_USER" \
        --dbname "$POSTGRES_DB" \
        --set=ON_ERROR_STOP=1 \
        --set=db_name="$db_name" <<'SQL'
SELECT format('CREATE DATABASE %I', :'db_name')
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = :'db_name'
)
\gexec
SQL

    echo "[OK] Database ready: $db_name"
done

echo "[OK] Restofy database initialization completed."
