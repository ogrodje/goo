set -ex

export NS=goo-prod

kubectl delete secret goo-secret --namespace=$NS --ignore-not-found

kubectl create secret generic --namespace=$NS \
  goo-secret \
  --from-literal=hygraph_endpoint=$HYGRAPH_ENDPOINT \
  --from-literal=sentry_auth_token=$SENTRY_AUTH_TOKEN

kubectl delete secret koofr-secret --namespace=$NS --ignore-not-found

kubectl create secret generic --namespace=$NS \
  koofr-secret \
  --from-literal=koofr_password=$KOOFR_PASSWORD \
  --from-literal=koofr_username=$KOOFR_USERNAME \
  --from-literal=webhook_url_1=$WEBHOOK_URL_1
