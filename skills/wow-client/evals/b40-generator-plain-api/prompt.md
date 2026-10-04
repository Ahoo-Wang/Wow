---
name: b40-generator-plain-api
tags: [behavior, generation, plain-api]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Generate a type-safe TypeScript API client from this OpenAPI spec. Use the wow-generator CLI to generate the client code into a src/generated/petstore directory. The generated code should include proper TypeScript types and an API client class for the pets endpoints.

Files from the user's project, shown inline:

`petstore.yaml`:

```yaml
openapi: 3.0.3
info:
  title: Pet Store API
  version: 1.0.0
paths:
  /pets:
    get:
      operationId: listPets
      tags:
        - pets
      summary: List all pets
      responses:
        '200':
          description: A list of pets
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Pet'
    post:
      operationId: createPet
      tags:
        - pets
      summary: Create a new pet
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/PetInput'
      responses:
        '201':
          description: Pet created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Pet'
  /pets/{petId}:
    get:
      operationId: getPet
      tags:
        - pets
      summary: Get a pet by ID
      parameters:
        - name: petId
          in: path
          required: true
          schema:
            type: string
      responses:
        '200':
          description: Pet details
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Pet'
        '404':
          description: Pet not found
    delete:
      operationId: deletePet
      tags:
        - pets
      summary: Delete a pet
      parameters:
        - name: petId
          in: path
          required: true
          schema:
            type: string
      responses:
        '204':
          description: Pet deleted
components:
  schemas:
    Pet:
      type: object
      properties:
        id:
          type: string
        name:
          type: string
        species:
          type: string
        age:
          type: integer
    PetInput:
      type: object
      required:
        - name
        - species
      properties:
        name:
          type: string
        species:
          type: string
        age:
          type: integer
```
