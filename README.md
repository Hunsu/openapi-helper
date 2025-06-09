# OpenApiHelper

**OpenApiHelper** is a personal plugin project for IntelliJ IDEA, designed to improve navigation, usability, and refactoring workflows when working with OpenAPI specifications. The primary goal is to align the plugin functionality with my specific development needs and workflows. While the features may not suit everyone's preferences, feel free to fork the repository and tailor it to your own use!

---

## **Features to Implement**
The following are features I aim to implement as part of this project. These features are primarily focused on enhancing navigation and refactoring capabilities for OpenAPI-based projects:

1. **Navigate from Endpoint Definitions to Implementations**
    - Support for **spring-kotlin generator** to quickly move from defined operations (e.g., `registerUser`) in OpenAPI specifications to the actual implementation in your codebase.

2. **Show Endpoint Usages**
    - Track and display usage of endpoints in **Kotlin** and **TypeScript** clients. This will help trace where and how specific endpoints are being utilized in your codebase.

3. **Navigation from Schema Components to Generated Classes**
    - Click on a component schema definition in your OpenAPI specification to directly jump to the corresponding generated class.

4. **Rename/Refactor Schema Components**
    - Seamlessly rename or refactor schema components defined in your OpenAPI specification, ensuring consistent updates across the entire project.

---

## **Please Note**
- **Personal Project**: This plugin is a personal initiative developed in my free time. As such, I focus on implementing features that solve problems I encounter in my everyday workflow.
- **Community Contributions**: While the plugin is tailored to my needs, I encourage you to **fork the repository** and extend it to fit your specific requirements.

---

## **How to Install**
1. Head to the [**Releases Page**](https://github.com/USERNAME/REPOSITORY/releases) (update this link with the actual repository path).
2. Download the latest `.zip` file of the plugin.
3. Open IntelliJ IDEA.
4. Navigate to `Preferences` > `Plugins` > `Install Plugin from Disk...`.
5. Select the downloaded `.zip` file and install.
6. Restart IntelliJ IDEA to activate the plugin.

---

## **Roadmap**
Below is a tentative roadmap for feature implementation:
- [x] Initial setup and basic navigation capabilities.
- [x] Full support for navigating from endpoint definitions to implementations.
- [x] Endpoint usage tracking for Kotlin and TypeScript.
- [ ] Component schema navigation to generated classes.
- [ ] Refactoring and renaming of schema components.

---

## **Contributing**
Contributions are welcome! If you'd like to contribute:
1. Fork the repository.
2. Clone and create a new branch for your feature or bug fix.
3. Make your changes and submit a pull request describing your updates.

---

## **License**
This plugin is licensed under MIT. Feel free to use, modify, and distribute the plugin as per the license terms.

---

Thank you for checking out **OpenApiHelper**! If you have any questions, issues, or suggestions, feel free to reach out or create an issue on GitHub.