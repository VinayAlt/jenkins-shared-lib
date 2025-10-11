def call(Map config = [:]) {    

    def call(Map cfg = [:]) {

  // ------- Required inputs (no silent defaults for these) -------
  def SERVICE_NAME     = required(cfg, 'serviceName')
  def GIT_URL          = required(cfg, 'gitUrl')
  def AWS_CRED_ID      = required(cfg, 'awsCredId')
  def AWS_REGION       = required(cfg, 'awsRegion')
  def ACCOUNT_ID       = required(cfg, 'accountId')
  def ECR_REPO         = required(cfg, 'ecrRepo')
  def K8S_PATH         = required(cfg, 'k8sPath')           // folder with deploy.yaml, svc.yaml, ingress.yaml
  def K8S_NAMESPACE    = required(cfg, 'k8sNamespace')
  def DEPLOYMENT_NAME  = required(cfg, 'deploymentName')

  // ------- Nice-to-have / defaults -------
  def GIT_BRANCH       = cfg.get('gitBranch', 'main')
  def DOCKERFILE       = cfg.get('dockerfile', 'Dockerfile')
  def IMAGE_TAG        = cfg.get('imageTag', env.BUILD_NUMBER)
  def DOCKER_AGENT     = cfg.get('dockerAgent', 'built-in')
  def DEPLOY_AGENT     = cfg.get('deployAgent', 'built-in')
  def CLUSTER_NAME     = cfg.get('clusterName', null) // if provided, we set kubeconfig
  def GIT_CRED_ID      = cfg.get('gitCredId', null)   // optional if public repo

  def IMAGE_URI = "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}"
    pipeline {
    
        agent any
    
        environment {
            AWS_REGION = "us-west-2"
            ACCOUNT_ID = "784074784226"
            ECR_REPO = "service-a"   // change for each service
            IMAGE_TAG = "${BUILD_NUMBER}"
        }
    
        stages {
            stage('Checkout') {
                steps {
                    git branch: 'main', url: 'https://github.com/VinayAlt/service-a.git'
                }
            }
    
            stage('Build Docker Image') {
                steps {
                    sh 'docker build -t $ECR_REPO:$IMAGE_TAG .'
                }
            }
    
            stage('Tag Image') {
                steps {
                    sh 'docker tag $ECR_REPO:$IMAGE_TAG $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG'
                }
            }
    
            stage('Push to ECR') {
                steps {
                    withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                        sh 'aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com'
                        sh 'docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG'
                    }
                }
            }
    
            stage('Deploy to Kubernetes') {
                steps {
                    sh '''
                    # Update deployment file with new image tag
                    sed -i "s/service-a:latest/service-a:${IMAGE_TAG}/g" k8s/deploy.yaml
    
                    # Apply deployment and service
                    kubectl apply -f k8s/deploy.yaml -n default
                    kubectl apply -f k8s/svc.yaml -n default
                    kubectl apply -f k8s/ingress.yaml -n default
    
                    # Wait until rollout is complete
                    kubectl rollout status deployment/opentelemetry-demo-frontendproxy-myapp -n default
                '''
                }
            }
        }
    }
}