SELECT
  CAR_ID, OWNER_ID, DAYS_ON_SITE_OWNER, ATC_SRPG, ATC_DETAIL, CAR_SRPG, CAR_INV_SHOWN, CAR_DETAIL, CAR_PRINT_DETAIL,
  CAR_WEB, CAR_MAP, CAR_PHONE, CAR_EMAIL, CAR_FAX, VIRTUAL_TOUR_CLICK_CNT, CHAT_PRSPCT_USED_CNT, CHAT_PRSPCT_NEW_CNT,
  MOBILE_VDP_IMP_CNT, MOBILE_FYC_MAP_CNT, FYC_SAVED_CNT, MOBILE_FYC_SAVED_CNT, MOBILE_CAR_SRP_CNT, MOBILE_EMAIL_FYC_CNT,
  SPOTLIGHT_LIFT_PCT, SPOTLIGHT_DAYS, SPOTLIGHT_IMP_CNT, CUSTOM_PHOTO_CNT, STOCK_PHOTO_CNT, MODIFIED_DATE, REPORT_MONTH,
  REPORT_FINAL
INTO "{{ work.path }}/{{ work.file.base }}.json.gz"
WITH GZIP COMPRESSION
WITH JSON FORMAT
FROM "{{ work.file.path }}"
WITH CSV FORMAT
