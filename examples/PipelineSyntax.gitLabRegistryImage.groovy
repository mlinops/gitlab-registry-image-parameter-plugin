// Pipeline Syntax / Snippet Generator template for gitlab-registry-image-parameter
//
// How to generate in Jenkins UI:
//   1. Open any Pipeline job → left menu "Pipeline Syntax"
//   2. Sample Step: "properties: Set job properties"
//   3. Check "This project is parameterized"
//   4. Add Parameter → "GitLab Registry Image Tag"
//   5. Fill the form → "Generate Pipeline Script"
//
// Symbol: gitLabRegistryImage
// Required: name, repoUrl, imageName
// Optional fields are omitted when left at defaults.
// Replace gitlab.example with your GitLab host.

properties([
    parameters([
        // Minimal (required only)
        gitLabRegistryImage(
            name: 'DMS_VERSION',
            repoUrl: 'https://gitlab.example/group/project.git',
            imageName: 'document-management-service'
        ),

        // Typical (credentials + default)
        gitLabRegistryImage(
            name: 'PMS_VERSION',
            description: 'Product Management Service image tag',
            repoUrl: 'https://gitlab.example/group/project.git',
            credentialsId: 'gitlab_api_token',
            imageName: 'product-management-service',
            defaultVersion: 'none'
        ),

        // With Advanced filters / limits
        gitLabRegistryImage(
            name: 'TMS_VERSION',
            description: '''Task Manager Service.
Use none to skip deploy.''',
            repoUrl: 'https://gitlab.example/group/project.git',
            credentialsId: 'gitlab_api_token',
            imageName: 'task-manager-service',
            defaultVersion: 'none',
            exclude: 'snapshot|rc',
            regex: '',
            perPage: 50,
            maxPages: 2,
            maxRows: 30,
            sortMode: 'NONE',
            connectTimeoutMs: 5000,
            readTimeoutMs: 5000
        )
    ])
])

pipeline {
    agent any
    stages {
        stage('Show') {
            steps {
                echo "DMS=${params.DMS_VERSION}"
                echo "PMS=${params.PMS_VERSION}"
                echo "TMS=${params.TMS_VERSION}"
            }
        }
    }
}
