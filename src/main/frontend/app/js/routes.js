'use strict';

app.config(['$routeProvider',
    function ($routeProvider) {
        $routeProvider
            .when('/', {templateUrl: 'assets/partials/welcome.html', controller: 'WelcomeCtrl'})
            .when('/import/:name', {templateUrl: 'assets/partials/import.html', controller: 'ImportCtrl'})
            .otherwise({redirectTo: '/'});
    }
]);
