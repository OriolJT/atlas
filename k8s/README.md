# Kubernetes Deployment

## Prerequisites
- kubectl configured
- Docker images built and available

## Deploy
```
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/secrets.yaml  # Edit with real values first
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/infrastructure/
kubectl apply -f k8s/identity-service/
kubectl apply -f k8s/workflow-service/
kubectl apply -f k8s/worker-service/
kubectl apply -f k8s/audit-service/
```

## Secrets

Before applying `secrets.yaml`, replace the placeholder base64 values with your real secrets:

```bash
echo -n 'your-jwt-secret' | base64
echo -n 'your-db-password' | base64
```

Then edit `k8s/secrets.yaml` with the output values.

## Local dev (minikube)

Build images directly into the minikube Docker daemon so `imagePullPolicy: IfNotPresent` works without a registry:

```bash
eval $(minikube docker-env)
docker build --target identity -t atlas-identity-service:latest .
docker build --target workflow -t atlas-workflow-service:latest .
docker build --target worker  -t atlas-worker-service:latest  .
docker build --target audit   -t atlas-audit-service:latest   .
```

## Teardown

```bash
kubectl delete namespace atlas
```
