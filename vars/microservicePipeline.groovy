// vars/microservicePipeline.groovy

def call(Map cfg = [:]) {
  // ------- Required inputs -------
  def SERVICE_NAME     = required(cfg, 'serviceName')
  def GIT_URL          = required(cfg, 'gitUrl')
  def AWS_CRED_ID      = required(cfg, 'awsCredId')
  def AWS_REGION       = required(cfg, 'awsRegion')
  def ACCOUNT_ID       = required(cfg, 'accountId')
  def ECR_REPO         = required(cfg, 'ecrRepo')
  def K8S_PATH         = required(cfg, 'k8sPath')            // contains deploy.yaml, svc.yaml, ingress.yaml
  def K8S_NAMESPACE    = required(cfg, 'k8sNamespace')
  def DEPLOYMENT_NAME  = required(cfg, 'deploymentName')

  // ------- Optional / defaults -------
  def GIT_BRANCH       = cfg.get('gitBranch', 'main')
  def DOCKERFILE       = cfg.get('dockerfile', 'Dockerfile')
  def IMAGE_TAG        = cfg.get('imageTag', env.BUILD_NUMBER)
  def DOCKER_AGENT     = cfg.get('dockerAgent', 'any')     // where Docker can run
  def DEPLOY_AGENT     = cfg.get('deployAgent', 'any')  // where kubectl can run
  def CLUSTER_NAME     = cfg.get('clusterName', null)        // if set, we update kubeconfig
  def GIT_CRED_ID      = cfg.get('gitCredId', null)          // needed if repo is private

  def REGISTRY_PREFIX  = "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  def IMAGE_URI        = "${REGISTRY_PREFIX}/${ECR_REPO}:${IMAGE_TAG}"
  def LATEST_URI       = "${REGISTRY_PREFIX}/${ECR_REPO}:latest"

  pipeline {
    agent any
    options { timestamps() }

    stages {

      stage('Checkout') {
        agent { label DOCKER_AGENT }
        steps {
          script {
            if (GIT_CRED_ID) {
              checkout([$class: 'GitSCM',
                userRemoteConfigs: [[url: GIT_URL, credentialsId: GIT_CRED_ID]],
                branches: [[name: "*/${GIT_BRANCH}"]]
              ])
            } else {
              git branch: GIT_BRANCH, url: GIT_URL
            }
          }
        }
      }

      stage('Build Docker Image') {
        agent { label DOCKER_AGENT }
        steps {
          sh """
            docker build -f ${DOCKERFILE} -t ${ECR_REPO}:${IMAGE_TAG} .
          """
        }
      }

      stage('Tag Image') {
        agent { label DOCKER_AGENT }
        steps {
          sh """
            docker tag ${ECR_REPO}:${IMAGE_TAG} ${IMAGE_URI}
          """
        }
      }

      stage('Push to ECR') {
        agent { label DOCKER_AGENT }
        steps {
          withAWS(credentials: AWS_CRED_ID, region: AWS_REGION) {
            sh """
              aws ecr get-login-password --region ${AWS_REGION} \
                | docker login --username AWS --password-stdin ${REGISTRY_PREFIX}
              docker push ${IMAGE_URI}
            """
          }
        }
      }

      stage('Deploy to Kubernetes') {
        agent { label DEPLOY_AGENT }
        steps {
          withAWS(credentials: AWS_CRED_ID, region: AWS_REGION) {
            script {
              if (CLUSTER_NAME) {
                sh "aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${AWS_REGION}"
              }
            }
            sh """
              # Swap :latest to the just-built image (exact match on full URI)
              sed -i "s#${LATEST_URI}#${IMAGE_URI}#g" ${K8S_PATH}/deploy.yaml

              kubectl apply -n ${K8S_NAMESPACE} -f ${K8S_PATH}/deploy.yaml
              if [ -f ${K8S_PATH}/svc.yaml ]; then kubectl apply -n ${K8S_NAMESPACE} -f ${K8S_PATH}/svc.yaml; fi
              if [ -f ${K8S_PATH}/ingress.yaml ]; then kubectl apply -n ${K8S_NAMESPACE} -f ${K8S_PATH}/ingress.yaml; fi
            """
          }
        }
      }

      stage('Wait for Rollout') {
        agent { label DEPLOY_AGENT }
        steps {
          sh "kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${K8S_NAMESPACE} --timeout=180s"
        }
      }
    }

    post {
      failure {
        echo "Deployment failed. Last events & pods:"
        sh """
          kubectl get events -n ${K8S_NAMESPACE} --sort-by=.metadata.creationTimestamp | tail -n 50 || true
          kubectl get pods -n ${K8S_NAMESPACE} -o wide || true
        """
      }
      always {
        echo "Built & deployed image: ${IMAGE_URI}"
      }
    }
  }
}

// helper to enforce required params
@NonCPS
def required(Map m, String key) {
  if (!m.containsKey(key) || m[key] == null || "${m[key]}".trim() == '') {
    error "Shared lib: missing required argument '${key}'"
  }
  m[key]
}