set -ex

export SECRET_NAME=goo-secret
export NS=goo-prod

kubectl delete secret $SECRET_NAME --namespace=$NS --ignore-not-found

kubectl create secret generic \
  --namespace=$NS $SECRET_NAME \
  --from-literal=hygraph_endpoint=$HYGRAPH_ENDPOINT \
  --from-literal=sentry_auth_token=$SENTRY_AUTH_TOKEN