set -ex

export NS=goo-prod

# goo-secret
kubectl delete secret goo-secret --namespace=$NS --ignore-not-found

kubectl create secret generic --namespace=$NS \
  goo-secret \
  --from-literal=hygraph_endpoint=$HYGRAPH_ENDPOINT \
  --from-literal=sentry_auth_token=$SENTRY_AUTH_TOKEN

# koofr-secret
kubectl delete secret koofr-secret --namespace=$NS --ignore-not-found

kubectl create secret generic --namespace=$NS \
  koofr-secret \
  --from-literal=koofr_password=$KOOFR_PASSWORD \
  --from-literal=koofr_username=$KOOFR_USERNAME \
  --from-literal=webhook_url_1=$WEBHOOK_URL_1

# koofr-secret
kubectl delete secret keycloak-secret --namespace=$NS --ignore-not-found

kubectl create secret generic --namespace=$NS \
  keycloak-secret \
  --from-literal=keycloak_admin=$KEYCLOAK_ADMIN \
  --from-literal=keycloak_admin_password=$KEYCLOAK_ADMIN_PASSWORD \
  --from-literal=keycloak_db=$KEYCLOAK_DB \
  --from-literal=keycloak_realm=$KEYCLOAK_REALM \
  --from-literal=keycloak_endpoint=$KEYCLOAK_ENDPOINT \
  --from-literal=kc_db_url=jdbc:postgresql://pg-one-postgresql.goo-prod.svc.cluster.local:5432/${KEYCLOAK_DB} \
  --from-literal=kc_db_username=$POSTGRES_USER \
  --from-literal=kc_db_password=$POSTGRES_PASSWORD

