SELECT SUM(CASE WHEN P_TYPE LIKE 'PROMO%%' THEN L_EXTENDEDPRICE*(1-L_DISCOUNT) ELSE 0.0 END), SUM(L_EXTENDEDPRICE*(1-L_DISCOUNT)) AS PROMO_REVENUE
FROM PART JOIN LINEITEM ON P_PARTKEY = L_PARTKEY 
WHERE L_SHIPDATE >= DATE '1994-03-01' AND L_SHIPDATE < DATE '1994-04-01'
